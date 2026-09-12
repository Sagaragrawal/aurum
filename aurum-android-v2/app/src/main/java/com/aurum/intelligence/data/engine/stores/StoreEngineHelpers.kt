package com.aurum.intelligence.data.engine.stores

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object StoreEngineHelpers {
    private const val TAG = "StoreEngineHelpers"

    suspend fun fetchStorePageWithRecovery(
        store: String,
        url: String,
        headers: Map<String, String>,
        maxRetries: Int = 3,
        activityRepository: RefreshActivityRepository? = null,
    ): ProductFetchResponse {
        var currentHeaders = headers
        var attempt = 0
        var delayMs = 600L

        while (attempt < maxRetries) {
            attempt++
            val response = runCatching {
                CronetNetworkClient.executeCronetWithHeaders(url, currentHeaders)
            }.getOrElse { e ->
                ProductFetchResponse(status = 500, body = e.message.orEmpty(), headers = emptyMap(), protocol = "error", durationMs = 0)
            }

            val isBlocked = response.status in setOf(403, 429, 503) ||
                (response.body.isNotBlank() && (
                    response.body.contains("<title>Access Denied</title>", ignoreCase = true) ||
                    response.body.contains("<title>Request Blocked</title>", ignoreCase = true) ||
                    response.body.contains("<title>Robot Check</title>", ignoreCase = true) ||
                    response.body.contains("<title>Human Verification</title>", ignoreCase = true) ||
                    response.body.contains("<h1>Please verify you are a human</h1>", ignoreCase = true)
                ))

            if (response.status in 200..299 && response.body.isNotBlank() && !isBlocked) {
                return response
            }

            if (attempt < maxRetries) {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    store,
                    "[$store] Request returned HTTP ${response.status} (attempt $attempt/$maxRetries). Retrying in ${delayMs}ms...",
                )

                CronetNetworkClient.resetSession()

                val config = ScraperConfigProvider.get()
                val baseStoreKey = store.substringBefore('.').lowercase()
                val freshBaseHeaders = config.stores[baseStoreKey]?.headers?.takeIf { it.isNotEmpty() }
                    ?: config.network.desktopHeaders
                val extraHeaders = headers.filterKeys { it.lowercase().startsWith("x-") || it.lowercase() == "pincode" || it.lowercase() == "referer" }
                currentHeaders = freshBaseHeaders + extraHeaders

                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(3000L)
            } else {
                return response
            }
        }

        return ProductFetchResponse(status = 403, body = "", headers = emptyMap(), protocol = "error", durationMs = 0)
    }

    suspend fun saveCandidates(
        database: AurumDatabase,
        store: String,
        candidates: List<ProductCandidate>,
        pincode: String?,
        distinctPids: MutableSet<String>? = null,
    ): Int {
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var validSaved = 0

        database.withTransaction {
            val allProducts = database.dao().allProducts()
            val byStoreAndRetailer = HashMap<String, ProductEntity>(allProducts.size * 2)
            val byRetailerId = HashMap<String, ProductEntity>(allProducts.size * 2)
            val byCanonicalUrl = HashMap<String, ProductEntity>(allProducts.size * 2)
            for (p in allProducts) {
                byStoreAndRetailer["${p.store}:${p.retailerId}"] = p
                byRetailerId[p.retailerId] = p
                if (p.canonicalUrl.isNotBlank()) {
                    byCanonicalUrl[p.canonicalUrl] = p
                }
            }

            val entitiesToUpsert = ArrayList<ProductEntity>(candidates.size)
            val historiesToInsert = ArrayList<ProductPriceHistoryEntity>()

            for (candidate in candidates) {
                try {
                    val cleanRetailerId = candidate.retailerId.substringBefore('_')
                    val existing = byStoreAndRetailer["$store:${candidate.retailerId}"]
                        ?: byStoreAndRetailer["$store:$cleanRetailerId"]
                        ?: byRetailerId[candidate.retailerId]
                        ?: byRetailerId[cleanRetailerId]
                        ?: (if (candidate.canonicalUrl.isNotBlank()) byCanonicalUrl[candidate.canonicalUrl] else null)

                    val entityId = existing?.id ?: UUID.randomUUID().toString()
                    val targetStore = store
                    val targetRetailerId = candidate.retailerId

                    val rawName = candidate.name ?: existing?.name ?: targetRetailerId
                    val isUnavailable = candidate.unavailable || ProductAvailability.isUnavailableName(rawName)
                    val isManual = (existing?.manuallyEditedAt ?: 0L) > 1788800000000L
                    val rawPrice = if (candidate.price > 0) candidate.price else existing?.price ?: 0.0

                    val rawGrams = if (isManual && existing?.grams != null) existing.grams else (candidate.grams ?: existing?.grams)
                    val rawKarat = candidate.karat ?: (if (isManual) existing?.karat else null)
                    val rawPurity = candidate.purity ?: (if (isManual) existing?.purity else null)

                    var normalizedKarat = 24.0
                    var normalizedPurity = "999"
                    var normalizedTitle = DatabaseSanitizerEngine.cleanTitle(rawName)

                    if (!isManual) {
                        val validation = Product24KValidator.validate(
                            name = rawName,
                            store = targetStore,
                            karat = rawKarat,
                            purity = rawPurity,
                            price = rawPrice,
                            grams = rawGrams,
                            brand = candidate.brand ?: existing?.brand,
                            canonicalUrl = candidate.canonicalUrl,
                            retailerId = targetRetailerId,
                        )
                        if (!validation.isValid) {
                            if (existing != null) {
                                database.dao().deleteProduct(existing.id)
                            }
                            continue
                        }
                        normalizedKarat = validation.normalizedKarat
                        normalizedPurity = validation.normalizedPurity
                        normalizedTitle = validation.normalizedTitle
                    }

                    val finalTitle = if (isManual && !existing?.name.isNullOrBlank()) existing.name else normalizedTitle
                    val finalGrams = rawGrams
                    val finalKarat = if (isManual && existing?.karat != null) existing.karat else normalizedKarat
                    val finalPurity = if (isManual && !existing?.purity.isNullOrBlank()) existing.purity else normalizedPurity
                    val finalUnitWeight = if (isManual) existing?.unitWeightGrams else (candidate.unitWeightGrams ?: existing?.unitWeightGrams)
                    val finalTotalWeight = if (isManual) existing?.totalWeightGrams else (candidate.totalWeightGrams ?: existing?.totalWeightGrams)
                    val finalQuantity = if (isManual && existing != null) existing.quantity else candidate.quantity
                    val finalManual = if (isManual) existing?.manuallyEditedAt else null

                    val entity = ProductEntity(
                        id = entityId,
                        store = targetStore,
                        retailerId = targetRetailerId,
                        canonicalUrl = if (candidate.canonicalUrl.isNotBlank()) candidate.canonicalUrl else existing?.canonicalUrl.orEmpty(),
                        name = finalTitle,
                        brand = candidate.brand ?: existing?.brand,
                        grams = finalGrams,
                        karat = finalKarat,
                        purity = finalPurity,
                        price = rawPrice,
                        couponPrice = candidate.couponPrice,
                        status = if (isUnavailable) "unavailable" else "live",
                        refreshMethod = "$store-native-parallel",
                        checkedAt = now,
                        lastLiveAt = if (!isUnavailable) now else existing?.lastLiveAt ?: 0,
                        manuallyEditedAt = finalManual,
                        unitWeightGrams = finalUnitWeight,
                        quantity = finalQuantity,
                        totalWeightGrams = finalTotalWeight,
                        weightConfidence = candidate.weightConfidence,
                        pincode = pincode ?: existing?.pincode,
                        latitude = existing?.latitude,
                        longitude = existing?.longitude,
                        formattedAddress = existing?.formattedAddress,
                        isBlinkDeal = candidate.isBlinkDeal,
                        blinkDealPrice = candidate.blinkDealPrice ?: existing?.blinkDealPrice,
                        blinkDealEndTime = existing?.blinkDealEndTime,
                        deliverable = !isUnavailable,
                        isMicroCoin = candidate.isMicroCoin,
                    )

                    byStoreAndRetailer["$targetStore:$targetRetailerId"] = entity
                    byRetailerId[targetRetailerId] = entity
                    if (entity.canonicalUrl.isNotBlank()) {
                        byCanonicalUrl[entity.canonicalUrl] = entity
                    }

                    entitiesToUpsert.add(entity)

                    if (existing == null || existing.price != candidate.price || existing.couponPrice != candidate.couponPrice) {
                        historiesToInsert.add(
                            ProductPriceHistoryEntity(
                                productId = entityId,
                                price = candidate.price,
                                couponPrice = candidate.couponPrice,
                                checkedAt = now,
                            )
                        )
                    }

                    if (distinctPids != null) {
                        if (!distinctPids.contains(targetRetailerId)) {
                            distinctPids.add(targetRetailerId)
                            validSaved++
                        }
                    } else {
                        validSaved++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to prepare product ${candidate.retailerId}: ${e.message}")
                }
            }

            if (entitiesToUpsert.isNotEmpty()) {
                database.dao().upsertProducts(entitiesToUpsert)
            }
            if (historiesToInsert.isNotEmpty()) {
                val validProductIds = database.dao().allProducts().map { it.id }.toSet() + entitiesToUpsert.map { it.id }.toSet()
                val safeHistories = historiesToInsert.filter { it.productId in validProductIds }
                if (safeHistories.isNotEmpty()) {
                    database.dao().insertPriceHistories(safeHistories)
                }
            }
        }

        return validSaved
    }

    suspend fun refreshStorePdp(
        database: AurumDatabase,
        internalDatabase: AurumInternalDatabase?,
        activityRepository: RefreshActivityRepository?,
        context: Context?,
        store: String,
        startedAt: Long,
        pincode: String?,
    ): PdpRefreshResult {
        val config = ScraperConfigProvider.get()
        val unrefreshed = database.dao().allProducts().filter { p ->
            p.store == store && p.status == "stale"
        }

        if (unrefreshed.isEmpty()) {
            database.dao().markStoreStaleProductsUnavailable(store)
            return PdpRefreshResult(0, 0, 0, 0)
        }

        activityRepository?.log(
            RefreshLogSeverity.Info,
            store,
            "[$store] Starting PDP verification for ${unrefreshed.size} items..."
        )

        val pincodeHeaders = pincode?.let { LocationHelper.buildPincodeHeaders(it) } ?: emptyMap()
        val ajioPdpHeaders = config.network.ajioPdpHeaders + pincodeHeaders

        var pdpRequests = 0
        var pdpUpdated = 0
        var pdpUnavailable = 0
        var pdpFailed = 0
        var consecutiveFailures = 0
        var abortPdp = false

        val concurrency = if (store == "ajio.com") 1 else config.limits.pdpConcurrency.coerceIn(1, 20)
        val semaphore = Semaphore(concurrency)

        coroutineScope {
            unrefreshed.chunked(concurrency * 2).forEach { chunk ->
                if (abortPdp) return@forEach
                chunk.map { product ->
                    async {
                        if (abortPdp) return@async
                        semaphore.withPermit {
                            val delayMs = when (store) {
                                "ajio.com" -> config.delays.ajioPageDelayMs
                                "amazon.in" -> config.delays.pdpAmazonDelayMs
                                else -> config.delays.pdpInterRequestDelayMs
                            }
                            delay(delayMs)

                            val endpoint = when (store) {
                                "ajio.com" -> {
                                    val cleanId = product.retailerId.substringBefore('_')
                                    val pattern = config.stores["ajio"]?.pdpUrlPattern?.takeIf { it.isNotBlank() } ?: "https://www.ajio.com/api/p/%s"
                                    if (pattern.contains("{id}")) pattern.replace("{id}", cleanId) else String.format(pattern, cleanId)
                                }
                                "myntra.com" -> {
                                    val pattern = config.stores["myntra"]?.pdpWebPattern?.takeIf { it.isNotBlank() } ?: "https://www.myntra.com/%s"
                                    if (pattern.contains("{id}")) pattern.replace("{id}", product.retailerId) else String.format(pattern, product.retailerId)
                                }
                                "amazon.in", "flipkart.com", "shopsy.in" -> product.canonicalUrl.takeIf { it.isNotBlank() }
                                else -> null
                            } ?: return@async

                            pdpRequests++
                            try {
                                val storeKey = store.substringBefore('.')
                                val storeConfigHeaders = config.stores[storeKey]?.headers?.takeIf { it.isNotEmpty() } ?: config.network.desktopHeaders
                                val storeHeaders = storeConfigHeaders + pincodeHeaders
                                val response = when (store) {
                                    "ajio.com" -> fetchStorePageWithRecovery("ajio.com", endpoint, ajioPdpHeaders, activityRepository = activityRepository)
                                    "shopsy.in" -> {
                                        val session = ShopsyCronetSession()
                                        session.bootstrap()
                                        if (pincode != null) session.setPincode(pincode, product.retailerId)
                                        session.fetchPdp(endpoint)
                                    }
                                    else -> fetchStorePageWithRecovery(store, endpoint, storeHeaders, activityRepository = activityRepository)
                                }

                                val ext = if (response.body.trimStart().startsWith("<") || response.body.trimStart().startsWith("<!")) "html" else "json"
                                DatabaseBackupManager.saveRawPage(store, "pdp_${product.retailerId}", response.body, ext)

                                if (response.status == 403 || response.status == 429) {
                                    consecutiveFailures++
                                    pdpFailed++
                                    if (consecutiveFailures >= config.limits.pdpMaxConsecutiveFailures) {
                                        activityRepository?.log(
                                            RefreshLogSeverity.Warning,
                                            store,
                                            "[$store] PDP verification hit HTTP ${response.status} ${config.limits.pdpMaxConsecutiveFailures} times. Gracefully aborting PDP."
                                        )
                                        CronetNetworkClient.resetSession()
                                        abortPdp = true
                                        return@async
                                    } else {
                                        delay(config.delays.ajioRateLimitBackoffMs)
                                        return@async
                                    }
                                } else {
                                    consecutiveFailures = 0
                                }

                                val now = System.currentTimeMillis()
                                when (val lookup = ProductLookup.parse(store, response.status, response.body, endpoint)) {
                                    is ProductLookup.Available -> {
                                        val isManual = (product.manuallyEditedAt ?: 0L) > 1788800000000L
                                        val targetGrams = if (isManual && product.grams != null) product.grams else (lookup.grams ?: product.grams)
                                        val validation = Product24KValidator.validate(
                                            name = if (isManual) product.name else (lookup.name ?: product.name),
                                            store = store,
                                            karat = lookup.karat ?: (if (isManual) product.karat else null),
                                            purity = lookup.purity ?: (if (isManual) product.purity else null),
                                            price = lookup.price,
                                            grams = targetGrams,
                                            brand = lookup.brand ?: product.brand,
                                            canonicalUrl = product.canonicalUrl,
                                            retailerId = product.retailerId,
                                        )
                                        if (!validation.isValid && !isManual) {
                                            database.dao().deleteProduct(product.id)
                                            return@async
                                        }
                                        val updatedProduct = product.copy(
                                            name = if (isManual) product.name else validation.normalizedTitle,
                                            karat = if (isManual && product.karat != null) product.karat else validation.normalizedKarat,
                                            purity = if (isManual && !product.purity.isNullOrBlank()) product.purity else validation.normalizedPurity,
                                            brand = lookup.brand ?: product.brand,
                                            price = lookup.price,
                                            couponPrice = lookup.couponPrice ?: product.couponPrice,
                                            grams = targetGrams,
                                            unitWeightGrams = if (isManual) product.unitWeightGrams else lookup.grams,
                                            totalWeightGrams = targetGrams,
                                            weightConfidence = if (isManual) product.weightConfidence else lookup.weightConfidence,
                                            status = "live",
                                            refreshMethod = lookup.refreshMethod,
                                            checkedAt = now,
                                            lastLiveAt = now,
                                            deliverable = true,
                                            isBlinkDeal = lookup.isBlinkDeal,
                                            blinkDealPrice = lookup.blinkDealPrice ?: product.blinkDealPrice,
                                            manuallyEditedAt = if (isManual) product.manuallyEditedAt else null,
                                        )
                                        database.dao().upsertProduct(updatedProduct)
                                        pdpUpdated++
                                    }
                                    is ProductLookup.Unavailable -> {
                                        database.dao().upsertProduct(
                                            product.copy(
                                                status = "unavailable",
                                                deliverable = false,
                                                checkedAt = now,
                                                price = lookup.price ?: product.price,
                                            )
                                        )
                                        pdpUnavailable++
                                    }
                                    is ProductLookup.RejectedNon24K -> {
                                        database.dao().deleteProduct(product.id)
                                    }
                                    ProductLookup.Unknown -> {
                                        pdpFailed++
                                    }
                                }
                            } catch (e: Exception) {
                                pdpFailed++
                                Log.w(TAG, "PDP verification error for ${product.retailerId}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        if (pdpUpdated > 0 || pdpUnavailable > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                store,
                "[$store] PDP verification complete: $pdpUpdated refreshed live, $pdpUnavailable marked unavailable, $pdpFailed failed/skipped"
            )
        }

        if (!abortPdp) {
            val demotedUnavailable = database.dao().markStoreStaleProductsUnavailable(store)
            if (demotedUnavailable > 0) {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    store,
                    "[$store] Reconciled catalogue: $demotedUnavailable stale items marked unavailable"
                )
            }
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                store,
                "[$store] PDP verification aborted due to rate limits; preserving remaining items."
            )
        }

        context?.let { ctx ->
            DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
        }

        return PdpRefreshResult(
            requests = pdpRequests,
            successful = pdpUpdated + pdpUnavailable,
            failed = pdpFailed,
            required = unrefreshed.size,
        )
    }

    suspend fun recordRawPayload(
        database: AurumDatabase,
        internalDatabase: AurumInternalDatabase?,
        id: String,
        store: String,
        json: String
    ) {
        val baseStore = store.substringBefore('_').substringBefore('.')
        if (!DatabaseBackupManager.shouldSaveRawPage(baseStore) && !DatabaseBackupManager.shouldSaveRawPage(store)) return
        val ext = if (json.trimStart().startsWith("<") || json.trimStart().startsWith("<!")) "html" else "json"
        DatabaseBackupManager.saveRawPage(store, id.take(8), json, ext)
        val payload = RawBridgePayloadEntity(
            id = id,
            store = store,
            receivedAt = System.currentTimeMillis(),
            json = json,
        )
        if (internalDatabase != null) {
            internalDatabase.dao().insertRawPayload(payload)
            internalDatabase.dao().trimRawPayloads(50)
        } else {
            database.dao().insertRawPayload(payload)
            database.dao().trimRawPayloads(50)
        }
    }
}
