package com.aurum.intelligence.data

import android.util.Log
import com.aurum.intelligence.parsers.AjioNativeParser
import com.aurum.intelligence.parsers.AmazonNativeParser
import com.aurum.intelligence.parsers.BullionNativeParser
import com.aurum.intelligence.parsers.FlipkartNativeParser
import com.aurum.intelligence.parsers.MyntraNativeParser
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class StoreRefreshProgress(
    val store: String,
    val itemsDiscovered: Int,
    val itemsValid: Int,
    val durationMs: Long,
    val isComplete: Boolean,
    val error: String? = null,
)

data class FullRefreshSummary(
    val storeResults: List<StoreRefreshProgress>,
    val bullionResults: Map<String, Double?>,
    val totalDiscovered: Int,
    val totalValid: Int,
    val totalDurationMs: Long,
)

class NativeParallelRefreshEngine(
    private val database: AurumDatabase,
    private val activityRepository: RefreshActivityRepository? = null,
) {

    private val tag = "ParallelRefreshEngine"

    suspend fun refreshAllParallel(
        pincode: String = "560048",
        latitude: Double? = null,
        longitude: Double? = null,
        maxPagesPerStore: Int = 3,
        onProgress: (StoreRefreshProgress) -> Unit = {},
    ): FullRefreshSummary = withContext(Dispatchers.IO) {
        val overallStart = System.currentTimeMillis()
        activityRepository?.log(RefreshLogSeverity.Info, null, "Starting 100% native parallel refresh for all stores and bullion")

        // 1. Get latest benchmark bullion rate for price plausibility evaluation
        val initialBullion = database.dao().latestBullionHistory()
        val initialBenchmarkRate = initialBullion?.price24

        // 2. Dispatch all 5 stores and bullion in parallel
        val (storeResults, bullionRates) = coroutineScope {
            val ajioDeferred = async { refreshAjio(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            val flipkartDeferred = async { refreshFlipkart(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            val shopsyDeferred = async { refreshShopsy(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            val amazonDeferred = async { refreshAmazon(initialBenchmarkRate, maxPagesPerStore, onProgress) }
            val myntraDeferred = async { refreshMyntra(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            val bullionDeferred = async { refreshBullion() }

            val stores = awaitAll(ajioDeferred, flipkartDeferred, shopsyDeferred, amazonDeferred, myntraDeferred)
            val bullion = bullionDeferred.await()
            stores to bullion
        }

        val totalDuration = System.currentTimeMillis() - overallStart
        val totalDiscovered = storeResults.sumOf { it.itemsDiscovered }
        val totalValid = storeResults.sumOf { it.itemsValid }

        activityRepository?.log(
            RefreshLogSeverity.Info,
            null,
            "Completed parallel refresh: $totalValid valid gold items found in ${totalDuration}ms across all stores",
        )

        FullRefreshSummary(
            storeResults = storeResults,
            bullionResults = bullionRates,
            totalDiscovered = totalDiscovered,
            totalValid = totalValid,
            totalDurationMs = totalDuration,
        )
    }

    // =========================================================================
    // AJIO ENGINE
    // =========================================================================
    private suspend fun refreshAjio(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null

        try {
            val baseUrl = "https://www.ajio.com/api/category/8303?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&cohortIds=nontransacted%7Cp_null%2Cfalse%2Cunisex%2Cnoasp&advfilter=true&platform=Android&showAdsOnNextPage=false&is_ads_enable_plp=true&displayRatings=true&store=ajio&pincode=$pincode&enableRushDelivery=true&vertexEnabled=false&previousSource=Saas"

            // Page 0
            val resp0 = CronetNetworkClient.executeCronetRequest("$baseUrl&currentPage=0", pincode)
            if (resp0.status in 200..299) {
                val parsed0 = AjioNativeParser.parse(resp0.body, bullionRate24)
                discovered += parsed0.candidates.size
                valid += saveCandidates("ajio.com", parsed0.candidates, pincode)

                val totalPages = parsed0.totalPages.coerceAtMost(maxPages)
                if (totalPages > 1) {
                    val semaphore = Semaphore(3)
                    coroutineScope {
                        (1 until totalPages).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&currentPage=$page", pincode)
                                    if (resp.status in 200..299) {
                                        val p = AjioNativeParser.parse(resp.body, bullionRate24)
                                        discovered += p.candidates.size
                                        valid += saveCandidates("ajio.com", p.candidates, pincode)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                lastError = "HTTP ${resp0.status}"
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Ajio refresh error: ${e.message}", e)
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("ajio.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        return result
    }

    // =========================================================================
    // FLIPKART ENGINE
    // =========================================================================
    private suspend fun refreshFlipkart(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null

        try {
            val baseUrl = "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold"

            val resp1 = CronetNetworkClient.executeCronetRequest(baseUrl, pincode)
            if (resp1.status in 200..299) {
                val parsed1 = FlipkartNativeParser.parse(resp1.body, "flipkart.com", bullionRate24)
                discovered += parsed1.candidates.size
                valid += saveCandidates("flipkart.com", parsed1.candidates, pincode)

                if (maxPages > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..maxPages).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&page=$page", pincode)
                                    if (resp.status in 200..299) {
                                        val p = FlipkartNativeParser.parse(resp.body, "flipkart.com", bullionRate24)
                                        discovered += p.candidates.size
                                        valid += saveCandidates("flipkart.com", p.candidates, pincode)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                lastError = "HTTP ${resp1.status}"
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Flipkart refresh error: ${e.message}", e)
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("flipkart.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        return result
    }

    // =========================================================================
    // SHOPSY ENGINE
    // =========================================================================
    private suspend fun refreshShopsy(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null

        try {
            val baseUrl = "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK"

            val resp1 = CronetNetworkClient.executeCronetRequest(baseUrl, pincode)
            if (resp1.status in 200..299) {
                val parsed1 = FlipkartNativeParser.parse(resp1.body, "shopsy.in", bullionRate24)
                discovered += parsed1.candidates.size
                valid += saveCandidates("shopsy.in", parsed1.candidates, pincode)

                if (maxPages > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..maxPages).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&page=$page", pincode)
                                    if (resp.status in 200..299) {
                                        val p = FlipkartNativeParser.parse(resp.body, "shopsy.in", bullionRate24)
                                        discovered += p.candidates.size
                                        valid += saveCandidates("shopsy.in", p.candidates, pincode)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                lastError = "HTTP ${resp1.status}"
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Shopsy refresh error: ${e.message}", e)
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("shopsy.in", discovered, valid, duration, true, lastError)
        onProgress(result)
        return result
    }

    // =========================================================================
    // AMAZON ENGINE
    // =========================================================================
    private suspend fun refreshAmazon(
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null

        try {
            val baseUrl = "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR"

            val resp1 = CronetNetworkClient.executeCronetRequest("$baseUrl&ref=sr_pg_1")
            if (resp1.status in 200..299) {
                val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                discovered += parsed1.candidates.size
                valid += saveCandidates("amazon.in", parsed1.candidates, null)

                if (maxPages > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..maxPages).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&page=$page&ref=sr_pg_$page")
                                    if (resp.status in 200..299) {
                                        val p = AmazonNativeParser.parse(resp.body, bullionRate24)
                                        discovered += p.candidates.size
                                        valid += saveCandidates("amazon.in", p.candidates, null)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                lastError = "HTTP ${resp1.status}"
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Amazon refresh error: ${e.message}", e)
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("amazon.in", discovered, valid, duration, true, lastError)
        onProgress(result)
        return result
    }

    // =========================================================================
    // MYNTRA ENGINE
    // =========================================================================
    private suspend fun refreshMyntra(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null

        try {
            // Step 1: Bootstrap page to obtain session cookies
            val bootstrapHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            )
            val bootstrap = CronetNetworkClient.executeCronetWithHeaders("https://www.myntra.com/gold-coin", bootstrapHeaders)
            
            // Step 2: Query gateway API
            val gatewayHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36",
                "Accept" to "application/json",
                "x-myntraweb" to "Yes",
                "x-requested-with" to "browser",
                "x-meta-app" to "channel=web",
                "Referer" to "https://www.myntra.com/gold-coin",
            )

            val gatewayUrl = "https://www.myntra.com/gateway/v4/search/gold-coin?rows=50&o=0&p=1&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode"
            val resp = CronetNetworkClient.executeCronetWithHeaders(gatewayUrl, gatewayHeaders)

            if (resp.status in 200..299) {
                val parsed = MyntraNativeParser.parse(resp.body, bullionRate24)
                discovered += parsed.candidates.size
                valid += saveCandidates("myntra.com", parsed.candidates, pincode)

                val totalCount = parsed.totalCount
                val totalPages = ((totalCount + 49) / 50).coerceAtMost(maxPages)
                if (totalPages > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..totalPages).map { page ->
                            val offset = (page - 1) * 50
                            async {
                                semaphore.withPermit {
                                    val pageUrl = "https://www.myntra.com/gateway/v4/search/gold-coin?rows=50&o=$offset&p=$page&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode"
                                    val r = CronetNetworkClient.executeCronetWithHeaders(pageUrl, gatewayHeaders)
                                    if (r.status in 200..299) {
                                        val p = MyntraNativeParser.parse(r.body, bullionRate24)
                                        discovered += p.candidates.size
                                        valid += saveCandidates("myntra.com", p.candidates, pincode)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                lastError = "Gateway HTTP ${resp.status}"
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Myntra refresh error: ${e.message}", e)
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("myntra.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        return result
    }

    // =========================================================================
    // BULLION ENGINE (4 Sources Completely Parallel)
    // =========================================================================
    private suspend fun refreshBullion(): Map<String, Double?> = coroutineScope {
        val malabarDeferred = async { refreshBullionSource("malabar", "https://www.malabargoldanddiamonds.com/graphql-magento?query=query%20getMetalRate(%24filter%3A%20MetalRateFilterInput)%20%7B%20getMetalRate(filter%3A%20%24filter)%20%7B%20items%20%7B%20entry_date%20entry_time%20purity%20unit%20rate%20country%20state%20%7D%20%7D%20%7D&variables=%7B%22filter%22%3A%7B%22metal_type%22%3A%22gold%22%2C%22country%22%3A%22India%22%7D%7D") }
        val mmtcDeferred = async { refreshBullionSource("mmtc", "https://www.mmtcpamp.com/gold-silver-rate-today") }
        val kalyanDeferred = async { refreshBullionSource("kalyan", "https://store.kalyanjewellers.net/gold-rate/india/en") }
        val tanishqDeferred = async { refreshBullionSource("tan", "https://www.tanishq.co.in/gold-rate.html") }

        mapOf(
            "malabar" to malabarDeferred.await(),
            "mmtc" to mmtcDeferred.await(),
            "kalyan" to kalyanDeferred.await(),
            "tan" to tanishqDeferred.await(),
        )
    }

    private suspend fun refreshBullionSource(sourceId: String, url: String): Double? {
        val now = System.currentTimeMillis()
        return try {
            val response = CronetNetworkClient.executeCronetRequest(url)
            if (response.status in 200..299) {
                val parsed = BullionNativeParser.parse(sourceId, response.body)
                if (parsed.price24 != null && parsed.price24 > 1000) {
                    val existing = database.dao().bullionSourceById(sourceId)
                    val label = when (sourceId) {
                        "malabar" -> "Malabar Gold & Diamonds"
                        "mmtc" -> "MMTC-PAMP"
                        "kalyan" -> "Kalyan Jewellers"
                        "tan" -> "Tanishq gold rate"
                        else -> sourceId.uppercase()
                    }

                    database.dao().upsertBullionSource(
                        BullionSourceEntity(
                            id = sourceId,
                            source = label,
                            label = label,
                            url = url,
                            price24 = parsed.price24,
                            price22 = parsed.price22,
                            price22Derived = parsed.price22Derived,
                            status = "live",
                            transport = "native-cronet",
                            fetchedAt = now,
                            lastLiveAt = now,
                            lastAttemptAt = now,
                            error = null,
                        )
                    )

                    database.dao().insertBullionHistory(
                        BullionHistoryEntity(
                            sourceId = sourceId,
                            price24 = parsed.price24,
                            price22 = parsed.price22 ?: (parsed.price24 * 22.0 / 24.0),
                            price22Derived = parsed.price22Derived,
                            fetchedAt = now,
                        )
                    )

                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        sourceId,
                        "Rendered bullion rate saved: 24K ₹${parsed.price24}/g, 22K ₹${parsed.price22}/g",
                    )

                    parsed.price24
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh bullion $sourceId: ${e.message}")
            null
        }
    }

    // =========================================================================
    // DATABASE INGESTION & DEDUPLICATION
    // =========================================================================
    private suspend fun saveCandidates(
        store: String,
        candidates: List<ProductCandidate>,
        pincode: String?,
    ): Int {
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var validSaved = 0

        for (candidate in candidates) {
            try {
                val existing = database.dao().productByRetailerId(store, candidate.retailerId)
                    ?: database.dao().productByCanonicalUrl(candidate.canonicalUrl)

                val entityId = existing?.id ?: UUID.randomUUID().toString()

                val entity = ProductEntity(
                    id = entityId,
                    store = store,
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
                    refreshMethod = "$store-native-parallel",
                    checkedAt = now,
                    lastLiveAt = if (!candidate.unavailable) now else existing?.lastLiveAt ?: 0,
                    manuallyEditedAt = existing?.manuallyEditedAt,
                    unitWeightGrams = candidate.unitWeightGrams ?: existing?.unitWeightGrams,
                    quantity = candidate.quantity,
                    totalWeightGrams = candidate.totalWeightGrams ?: existing?.totalWeightGrams,
                    weightConfidence = candidate.weightConfidence,
                    pincode = pincode ?: existing?.pincode,
                    latitude = existing?.latitude,
                    longitude = existing?.longitude,
                    formattedAddress = existing?.formattedAddress,
                    isBlinkDeal = candidate.isBlinkDeal,
                    blinkDealPrice = candidate.blinkDealPrice ?: existing?.blinkDealPrice,
                    blinkDealEndTime = existing?.blinkDealEndTime,
                    deliverable = !candidate.unavailable,
                    isMicroCoin = candidate.isMicroCoin,
                )

                database.dao().upsertProduct(entity)

                // Track price history if changed
                if (existing == null || existing.price != candidate.price || existing.couponPrice != candidate.couponPrice) {
                    if (!database.dao().hasPriceHistory(entityId, candidate.price, candidate.couponPrice, now)) {
                        database.dao().insertPriceHistory(
                            ProductPriceHistoryEntity(
                                productId = entityId,
                                price = candidate.price,
                                couponPrice = candidate.couponPrice,
                                checkedAt = now,
                            )
                        )
                    }
                }

                validSaved++
            } catch (e: Exception) {
                Log.w(tag, "Failed to save product ${candidate.retailerId}: ${e.message}")
            }
        }

        return validSaved
    }
}
