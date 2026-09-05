package com.aurum.intelligence.data

import androidx.room.withTransaction
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class BridgeMergeResult(
    val received: Int,
    val accepted: Int,
    val updated: Int,
    val discovered: Int,
    val skipped: Int,
    val rejectionCounts: Map<String, Int>,
    val acceptedIdentityKeys: Set<String>,
)

data class ArchiveImportResult(
    val productsAdded: Int,
    val productsMerged: Int,
    val historyAdded: Int,
    val rawPayloadsAdded: Int,
    val bullionSourcesMerged: Int,
    val bullionHistoryAdded: Int,
)

// Pure in-memory session authorization, independent of Room so its begin/allow/end contract is
// unit testable without Android/Robolectric test infrastructure.
class RefreshSessionRegistry {
    private data class Session(val stores: Set<String>, val productIds: Set<String>)
    private val activeSessions = mutableMapOf<String, Session>()

    @Synchronized
    fun begin(sessionId: String, stores: Set<String>, productIds: Set<String> = emptySet()) {
        require(sessionId.isNotBlank()) { "Refresh session ID is required" }
        activeSessions[sessionId] = Session(stores, productIds)
    }

    // Synchronous and immediate: once this returns, the session is no longer authorized for any store.
    @Synchronized
    fun end(sessionId: String) {
        activeSessions.remove(sessionId)
    }

    @Synchronized
    fun requireAllowed(sessionId: String, store: String): Set<String> {
        val session = activeSessions[sessionId] ?: throw IllegalArgumentException("Refresh session is inactive or unknown")
        require(store in session.stores) { "Refresh session does not allow $store" }
        return session.productIds
    }
}

class BridgeRepository(private val database: AurumDatabase) {
    val products = database.dao().observeProducts()
    private val mutableMergeEvents = MutableSharedFlow<BridgeMergeEvent>(extraBufferCapacity = 16)
    val mergeEvents = mutableMergeEvents.asSharedFlow()
    private val sessions = RefreshSessionRegistry()
    private val missingCatalogueProductVerifier = MissingCatalogueProductVerifier(database)

    fun beginRefreshSession(sessionId: String, stores: Set<String>, productIds: Set<String> = emptySet()) =
        sessions.begin(sessionId, stores, productIds)

    fun endRefreshSession(sessionId: String) = sessions.end(sessionId)

    suspend fun refreshMissingCatalogueProducts(
        store: String,
        acceptedIdentityKeys: Set<String>,
        targetProductIds: Set<String>,
        fetcher: suspend (String) -> ProductFetchResponse?,
        onProgress: suspend (Int, Int, ProductEntity) -> Unit = { _, _, _ -> },
    ): MissingCatalogueProductResult = missingCatalogueProductVerifier.refreshMissingProducts(store, acceptedIdentityKeys, targetProductIds, fetcher, onProgress)

    suspend fun refreshProducts(
        store: String,
        productIds: Set<String>,
        fetcher: suspend (String) -> ProductFetchResponse?,
    ): MissingCatalogueProductResult = missingCatalogueProductVerifier.refreshProducts(store, productIds, fetcher)

    suspend fun refreshProduct(
        productId: String,
        fetcher: suspend (String) -> ProductFetchResponse?,
    ): ProductLookup = missingCatalogueProductVerifier.refreshProduct(productId, fetcher)

    suspend fun exportArchive(output: OutputStream) {
        val archive = database.withTransaction {
            AurumArchive(
                manifest = ArchiveManifest(exportedAt = System.currentTimeMillis()),
                products = database.dao().allProducts().map(ProductEntity::toArchiveProduct),
                history = database.dao().allProductHistory().map(ProductPriceHistoryEntity::toArchiveHistory),
                rawBridgePayloads = database.dao().allRawPayloads().map(RawBridgePayloadEntity::toArchivePayload),
                bullionSources = database.dao().allBullionSources().map(BullionSourceEntity::toArchiveBullionSource),
                bullionHistory = database.dao().allBullionHistory().map(BullionHistoryEntity::toArchiveBullionHistory),
            )
        }
        AurumArchiveCodec.encode(archive, output)
    }

    suspend fun importArchive(input: InputStream): ArchiveImportResult {
        val archive = AurumArchiveCodec.decode(input)
        return database.withTransaction {
            var productsAdded = 0
            var productsMerged = 0
            var historyAdded = 0
            var rawPayloadsAdded = 0
            var bullionSourcesMerged = 0
            var bullionHistoryAdded = 0
            val localProductIds = mutableMapOf<String, String>()

            archive.products.forEach { imported ->
                val retailerMatch = database.dao().productByRetailerId(imported.store, imported.retailerId)
                val urlMatch = database.dao().productByCanonicalUrl(imported.canonicalUrl)
                val existing = retailerMatch ?: urlMatch
                val merged = imported.mergeWith(existing, canonicalUrlConflict = urlMatch != null && urlMatch.id != existing?.id)
                database.dao().upsertProduct(merged)
                localProductIds[imported.id] = merged.id
                if (existing == null) productsAdded += 1 else productsMerged += 1
            }

            archive.history.forEach { imported ->
                val productId = localProductIds[imported.productId] ?: return@forEach
                if (!database.dao().hasPriceHistory(productId, imported.price, imported.couponPrice, imported.checkedAt)) {
                    database.dao().insertPriceHistory(
                        ProductPriceHistoryEntity(
                            productId = productId,
                            price = imported.price,
                            couponPrice = imported.couponPrice,
                            checkedAt = imported.checkedAt,
                        ),
                    )
                    historyAdded += 1
                }
            }
            archive.rawBridgePayloads.forEach { imported ->
                val inserted = database.dao().insertRawPayload(
                    RawBridgePayloadEntity(imported.id, imported.store, imported.receivedAt, imported.json),
                )
                if (inserted != -1L) rawPayloadsAdded += 1
            }
            database.dao().trimRawPayloads(MAX_RETAINED_RAW_PAYLOADS)
            archive.bullionSources.forEach { imported ->
                val existing = database.dao().bullionSourceById(imported.id)
                database.dao().upsertBullionSource(imported.mergeWith(existing))
                bullionSourcesMerged += 1
            }
            archive.bullionHistory.forEach { imported ->
                if (!database.dao().hasBullionHistory(imported.sourceId, imported.price24, imported.price22, imported.fetchedAt)) {
                    database.dao().insertBullionHistory(imported.toEntity())
                    bullionHistoryAdded += 1
                }
            }
            ArchiveImportResult(
                productsAdded,
                productsMerged,
                historyAdded,
                rawPayloadsAdded,
                bullionSourcesMerged,
                bullionHistoryAdded,
            )
        }
    }

    suspend fun merge(rawJson: String, sessionId: String): BridgeMergeResult {
        val result = database.withTransaction {
        val latestBullion = database.dao().latestBullionHistory()
        val bullionRate24 = latestBullion?.price24

        val payload = BridgePayloadParser.parse(rawJson)
        val allowedProductIds = requireSessionAllows(sessionId, payload.store)
        val allowedRetailerIds = if (allowedProductIds.isEmpty()) {
            emptySet()
        } else {
            database.dao().allProducts()
                .filter { it.id in allowedProductIds && it.store == payload.store }
                .mapTo(linkedSetOf(), ProductEntity::retailerId)
        }
        val now = System.currentTimeMillis()
        database.dao().insertRawPayload(
            RawBridgePayloadEntity(
                id = UUID.randomUUID().toString(),
                store = payload.store,
                receivedAt = now,
                json = rawJson,
            ),
        )
        database.dao().trimRawPayloads(MAX_RETAINED_RAW_PAYLOADS)

        var updated = 0
        var discovered = 0
        var skipped = 0
        val rejectionCounts = linkedMapOf<String, Int>()
        val acceptedIdentityKeys = linkedSetOf<String>()
        payload.records.forEach { record ->
            val parsed = record.toProductCandidate(payload.store, bullionRate24)
            val candidate = (parsed as? CandidateParseResult.Valid)?.candidate
            if (candidate == null) {
                skipped += 1
                val reason = (parsed as? CandidateParseResult.Rejected)?.reason ?: "invalid_record"
                rejectionCounts[reason] = (rejectionCounts[reason] ?: 0) + 1
                return@forEach
            }
            if (allowedRetailerIds.isNotEmpty() && candidate.retailerId !in allowedRetailerIds) {
                skipped += 1
                rejectionCounts["outside_refresh_selection"] = (rejectionCounts["outside_refresh_selection"] ?: 0) + 1
                return@forEach
            }
            val identityKey = "${payload.store}:${candidate.retailerId}"
            if (!acceptedIdentityKeys.add(identityKey)) {
                skipped += 1
                rejectionCounts["duplicate_identity"] = (rejectionCounts["duplicate_identity"] ?: 0) + 1
                return@forEach
            }
            val retailerMatch = database.dao().productByRetailerId(payload.store, candidate.retailerId)
            val urlMatch = database.dao().productByCanonicalUrl(candidate.canonicalUrl)
            if (retailerMatch != null && urlMatch != null && retailerMatch.id != urlMatch.id) {
                skipped += 1
                acceptedIdentityKeys.remove(identityKey)
                rejectionCounts["identity_conflict"] = (rejectionCounts["identity_conflict"] ?: 0) + 1
                return@forEach
            }
            val existing = retailerMatch ?: urlMatch
            val product = ProductEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                store = payload.store,
                retailerId = candidate.retailerId,
                canonicalUrl = candidate.canonicalUrl,
                name = candidate.name ?: existing?.name ?: candidate.retailerId,
                brand = candidate.brand ?: existing?.brand,
                grams = if (existing?.manuallyEditedAt != null) existing.grams else candidate.grams ?: existing?.grams,
                karat = if (existing?.manuallyEditedAt != null) existing.karat else candidate.karat ?: existing?.karat,
                purity = if (existing?.manuallyEditedAt != null) existing.purity else candidate.purity ?: existing?.purity,
                price = candidate.price,
                couponPrice = candidate.couponPrice,
                status = when {
                    candidate.unavailable -> "unavailable"
                    else -> "live"
                },
                refreshMethod = "${payload.store}-browser-bridge",
                checkedAt = now,
                lastLiveAt = if (!candidate.unavailable) now else existing?.lastLiveAt ?: 0,
                manuallyEditedAt = existing?.manuallyEditedAt,
            )
            database.dao().upsertProduct(product)
            if (existing == null || existing.price != product.price || existing.couponPrice != product.couponPrice) {
                database.dao().insertPriceHistory(
                    ProductPriceHistoryEntity(
                        productId = product.id,
                        price = product.price,
                        couponPrice = product.couponPrice,
                        checkedAt = now,
                    ),
                )
            }
            if (existing == null) discovered += 1 else updated += 1
        }
            // Re-checked immediately before returning from the transaction lambda: this narrows the
            // cancel/commit race to the sliver between this line and Room's own commit of the
            // withTransaction block, which cannot itself be gated by an in-memory check without holding
            // a lock across a suspension point (unsafe) or moving session state into Room (a larger,
            // deliberately deferred redesign - see item 5 of the 4.9.13 pass). A cancellation landing in
            // that final sliver can still commit; this is a known, documented, unresolved residual race.
            requireSessionAllows(sessionId, payload.store)
            BridgeMergeResult(payload.records.size, acceptedIdentityKeys.size, updated, discovered, skipped, rejectionCounts, acceptedIdentityKeys) to payload.store
        }
        mutableMergeEvents.emit(BridgeMergeEvent(sessionId, result.second, result.first))
        return result.first
    }

    private fun requireSessionAllows(sessionId: String, store: String): Set<String> = sessions.requireAllowed(sessionId, store)

    private companion object {
        const val MAX_RETAINED_RAW_PAYLOADS = 20
    }
}

data class BridgeMergeEvent(val sessionId: String, val store: String, val result: BridgeMergeResult)

private fun ProductEntity.toArchiveProduct() = ArchiveProduct(
    id = id,
    store = store,
    retailerId = retailerId,
    canonicalUrl = canonicalUrl,
    name = name,
    brand = brand,
    grams = grams,
    karat = karat,
    purity = purity,
    price = price,
    couponPrice = couponPrice,
    status = status,
    refreshMethod = refreshMethod,
    checkedAt = checkedAt,
    lastLiveAt = lastLiveAt,
    manuallyEditedAt = manuallyEditedAt,
)

private fun ProductPriceHistoryEntity.toArchiveHistory() = ArchiveProductHistory(
    productId = productId,
    price = price,
    couponPrice = couponPrice,
    checkedAt = checkedAt,
)

private fun RawBridgePayloadEntity.toArchivePayload() = ArchiveRawBridgePayload(id, store, receivedAt, json)

private fun BullionSourceEntity.toArchiveBullionSource() = ArchiveBullionSource(
    id, source, label, url, price24, price22, price22Derived, status, transport,
    fetchedAt, lastLiveAt, lastAttemptAt, error,
)

private fun BullionHistoryEntity.toArchiveBullionHistory() = ArchiveBullionHistory(
    sourceId, price24, price22, price22Derived, fetchedAt,
)

private fun ArchiveBullionHistory.toEntity() = BullionHistoryEntity(
    sourceId = sourceId,
    price24 = price24,
    price22 = price22,
    price22Derived = price22Derived,
    fetchedAt = fetchedAt,
)

private fun ArchiveBullionSource.mergeWith(existing: BullionSourceEntity?): BullionSourceEntity {
    val importedIsNewer = (fetchedAt ?: 0) >= (existing?.fetchedAt ?: 0)
    return BullionSourceEntity(
        id = id,
        source = source,
        label = label,
        url = url,
        price24 = if (importedIsNewer) price24 else existing?.price24,
        price22 = if (importedIsNewer) price22 else existing?.price22,
        price22Derived = if (importedIsNewer) price22Derived else existing?.price22Derived ?: false,
        status = if (importedIsNewer) status else existing?.status ?: status,
        transport = transport,
        fetchedAt = maxOf(fetchedAt ?: 0, existing?.fetchedAt ?: 0).takeIf { it > 0 },
        lastLiveAt = maxOf(lastLiveAt ?: 0, existing?.lastLiveAt ?: 0).takeIf { it > 0 },
        lastAttemptAt = maxOf(lastAttemptAt ?: 0, existing?.lastAttemptAt ?: 0).takeIf { it > 0 },
        error = if (importedIsNewer) error else existing?.error,
    )
}

private fun ArchiveProduct.mergeWith(existing: ProductEntity?, canonicalUrlConflict: Boolean): ProductEntity {
    if (existing == null) {
        return ProductEntity(
            id = UUID.randomUUID().toString(),
            store = store,
            retailerId = retailerId,
            canonicalUrl = canonicalUrl,
            name = name,
            brand = brand,
            grams = grams,
            karat = karat,
            purity = purity,
            price = price,
            couponPrice = couponPrice,
            status = status,
            refreshMethod = refreshMethod,
            checkedAt = checkedAt,
            lastLiveAt = lastLiveAt,
            manuallyEditedAt = manuallyEditedAt,
        )
    }
    val importIsNewer = checkedAt >= existing.checkedAt
    val manual = existing.manuallyEditedAt != null
    return ProductEntity(
        id = existing.id,
        store = existing.store,
        retailerId = if (manual) existing.retailerId else retailerId,
        canonicalUrl = if (manual || canonicalUrlConflict) existing.canonicalUrl else canonicalUrl,
        name = if (manual) existing.name else name,
        brand = if (manual) existing.brand else brand,
        grams = if (manual) existing.grams else grams,
        karat = if (manual) existing.karat else karat,
        purity = if (manual) existing.purity else purity,
        price = if (importIsNewer) price else existing.price,
        couponPrice = if (importIsNewer) couponPrice else existing.couponPrice,
        status = if (importIsNewer) status else existing.status,
        refreshMethod = if (importIsNewer) refreshMethod else existing.refreshMethod,
        checkedAt = maxOf(checkedAt, existing.checkedAt),
        lastLiveAt = maxOf(lastLiveAt, existing.lastLiveAt),
        manuallyEditedAt = listOfNotNull(manuallyEditedAt, existing.manuallyEditedAt).maxOrNull(),
    )
}