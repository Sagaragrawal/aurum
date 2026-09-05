package com.aurum.intelligence.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class StoreApiRefreshSummary(
    val store: String,
    val totalDiscovered: Int,
    val validCount: Int,
    val durationMs: Long,
    val success: Boolean,
    val error: String? = null,
)

data class FullDirectRefreshResult(
    val summaries: List<StoreApiRefreshSummary>,
    val totalProductsDiscovered: Int,
    val totalProductsValid: Int,
    val durationMs: Long,
)

class DirectApiRefreshCoordinator(private val database: AurumDatabase) {

    suspend fun refreshAllStoresParallel(
        pincode: String = "560048",
        latitude: Double? = 12.9716,
        longitude: Double? = 77.5946,
        onStoreCompleted: (StoreApiRefreshSummary) -> Unit = {},
    ): FullDirectRefreshResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val adapters = StoreRegistry.getAll()

        val results = coroutineScope {
            adapters.map { adapter ->
                async {
                    val storeStart = System.currentTimeMillis()
                    val result = refreshStoreDirect(adapter, pincode, latitude, longitude)
                    val duration = System.currentTimeMillis() - storeStart
                    val summary = StoreApiRefreshSummary(
                        store = adapter.storeName,
                        totalDiscovered = result.first,
                        validCount = result.second,
                        durationMs = duration,
                        success = result.third == null,
                        error = result.third,
                    )
                    onStoreCompleted(summary)
                    summary
                }
            }.awaitAll()
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val totalDiscovered = results.sumOf { it.totalDiscovered }
        val totalValid = results.sumOf { it.validCount }

        FullDirectRefreshResult(
            summaries = results,
            totalProductsDiscovered = totalDiscovered,
            totalProductsValid = totalValid,
            durationMs = totalDuration,
        )
    }

    private suspend fun refreshStoreDirect(
        adapter: StoreAdapter,
        pincode: String,
        latitude: Double?,
        longitude: Double?,
    ): Triple<Int, Int, String?> = withContext(Dispatchers.IO) {
        val searchUrls = adapter.getSearchUrls(pincode)
        if (searchUrls.isEmpty()) return@withContext Triple(0, 0, null)

        var discovered = 0
        var valid = 0
        var lastError: String? = null

        for (targetUrl in searchUrls) {
            val response = fetchApiEndpoint(targetUrl, pincode, latitude, longitude)
            if (response == null || response.status !in 200..299) {
                lastError = "HTTP ${response?.status ?: 0} at $targetUrl"
                continue
            }

            try {
                val latestBullion = database.dao().latestBullionHistory()
                val bullionRate24 = latestBullion?.price24

                val payload = BridgePayloadParser.parse(response.body)
                val candidates = payload.records.mapNotNull { rec ->
                    when (val parsed = rec.toProductCandidate(adapter.storeName, bullionRate24)) {
                        is CandidateParseResult.Valid -> parsed.candidate
                        is CandidateParseResult.Rejected -> null
                    }
                }

                discovered += payload.records.size
                valid += candidates.size

                val now = System.currentTimeMillis()
                candidates.forEach { candidate ->
                    val existing = database.dao().productByRetailerId(adapter.storeName, candidate.retailerId)
                        ?: database.dao().productByCanonicalUrl(candidate.canonicalUrl)

                    database.dao().upsertProduct(
                        ProductEntity(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            store = adapter.storeName,
                            retailerId = candidate.retailerId,
                            canonicalUrl = candidate.canonicalUrl,
                            name = candidate.name ?: existing?.name ?: candidate.retailerId,
                            brand = candidate.brand ?: existing?.brand,
                            grams = candidate.grams ?: existing?.grams,
                            karat = candidate.karat ?: existing?.karat,
                            purity = candidate.purity ?: existing?.purity,
                            price = candidate.price,
                            couponPrice = candidate.couponPrice,
                            status = if (candidate.unavailable) "unavailable" else "live",
                            refreshMethod = "${adapter.storeName}-direct-api",
                            checkedAt = now,
                            lastLiveAt = if (!candidate.unavailable) now else existing?.lastLiveAt ?: 0,
                            unitWeightGrams = candidate.unitWeightGrams ?: existing?.unitWeightGrams,
                            quantity = candidate.quantity,
                            totalWeightGrams = candidate.totalWeightGrams ?: existing?.totalWeightGrams,
                            weightConfidence = candidate.weightConfidence,
                            pincode = pincode,
                            latitude = latitude,
                            longitude = longitude,
                            isBlinkDeal = candidate.isBlinkDeal,
                            blinkDealPrice = candidate.blinkDealPrice ?: existing?.blinkDealPrice,
                            deliverable = !candidate.unavailable,
                            isMicroCoin = candidate.isMicroCoin,
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }

        Triple(discovered, valid, if (valid > 0) null else lastError)
    }

    private suspend fun fetchApiEndpoint(
        targetUrl: String,
        pincode: String,
        latitude: Double?,
        longitude: Double?,
    ): ProductFetchResponse? = withTimeoutOrNull(5000L) {
        return@withTimeoutOrNull CronetNetworkClient.executeCronetRequest(targetUrl, pincode, latitude, longitude)
    }
}
