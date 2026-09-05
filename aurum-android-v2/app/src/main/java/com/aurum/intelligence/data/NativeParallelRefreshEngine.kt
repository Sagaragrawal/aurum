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
    // AJIO ENGINE (4-5 Target Links: Category 8303, Jewellery, Girls, Boys, Search)
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

        data class AjioTarget(val name: String, val baseUrl: String)
        val ajioTargets = listOf(
            AjioTarget(
                "Women Gold Category 8303",
                "https://www.ajio.com/api/category/8303?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&cohortIds=nontransacted%7Cp_null%2Cfalse%2Cunisex%2Cnoasp&advfilter=true&platform=Android&showAdsOnNextPage=false&is_ads_enable_plp=true&displayRatings=true&store=ajio&pincode=$pincode&enableRushDelivery=true&vertexEnabled=false&previousSource=Saas"
            ),
            AjioTarget(
                "Jewellery Section 176606",
                "https://www.ajio.com/api/category/176606?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            AjioTarget(
                "Girls Jewellery 169379",
                "https://www.ajio.com/api/category/169379?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            AjioTarget(
                "Boys Jewellery 169373",
                "https://www.ajio.com/api/category/169373?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            AjioTarget(
                "Search Gold Coin API",
                "https://www.ajio.com/api/search?fields=SITE&pageSize=45&format=json&query=%3Arelevance&gridColumns=3&advfilter=true&platform=Android&store=ajio&pincode=$pincode&text=gold%20coin"
            )
        )

        val semaphore = Semaphore(3)
        coroutineScope {
            ajioTargets.map { target ->
                async {
                    semaphore.withPermit {
                        val urlStart = System.currentTimeMillis()
                        try {
                            activityRepository?.log(
                                RefreshLogSeverity.Info,
                                "ajio.com",
                                "[AJIO] Fetching ${target.name} (page 0)..."
                            )
                            val resp0 = CronetNetworkClient.executeCronetRequest("${target.baseUrl}&currentPage=0", pincode)
                            if (resp0.status in 200..299) {
                                val parsed0 = AjioNativeParser.parse(resp0.body, bullionRate24)
                                var urlDiscovered = parsed0.candidates.size
                                var urlValid = saveCandidates("ajio.com", parsed0.candidates, pincode)

                                synchronized(this@NativeParallelRefreshEngine) {
                                    discovered += urlDiscovered
                                    valid += urlValid
                                }

                                val pagesToFetch = parsed0.totalPages.coerceAtMost(maxPages.coerceAtLeast(8))
                                if (pagesToFetch > 1) {
                                    (1 until pagesToFetch).forEach { page ->
                                        val pResp = CronetNetworkClient.executeCronetRequest("${target.baseUrl}&currentPage=$page", pincode)
                                        if (pResp.status in 200..299) {
                                            val pParsed = AjioNativeParser.parse(pResp.body, bullionRate24)
                                            val pSaved = saveCandidates("ajio.com", pParsed.candidates, pincode)
                                            urlDiscovered += pParsed.candidates.size
                                            urlValid += pSaved
                                            synchronized(this@NativeParallelRefreshEngine) {
                                                discovered += pParsed.candidates.size
                                                valid += pSaved
                                            }
                                        }
                                    }
                                }

                                val urlElapsed = System.currentTimeMillis() - urlStart
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "ajio.com",
                                    "[AJIO] ${target.name}: $urlDiscovered discovered, $urlValid valid saved ($pagesToFetch pages, ${urlElapsed}ms)"
                                )
                            } else {
                                lastError = "HTTP ${resp0.status}"
                                activityRepository?.log(
                                    RefreshLogSeverity.Warning,
                                    "ajio.com",
                                    "[AJIO] ${target.name} returned HTTP ${resp0.status}"
                                )
                            }
                        } catch (e: Exception) {
                            lastError = e.message
                            Log.e(tag, "Ajio refresh error for ${target.name}: ${e.message}", e)
                            activityRepository?.log(
                                RefreshLogSeverity.Error,
                                "ajio.com",
                                "[AJIO] ${target.name} failed: ${e.message}"
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("ajio.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "ajio.com",
                "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "ajio.com",
                "Existing prices preserved: catalogue scan complete (${duration}ms)",
            )
        }
        return result
    }

    // =========================================================================
    // FLIPKART ENGINE (1 Main Coins Site + 1 Minutes Site)
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

        data class FkTarget(val name: String, val url: String, val isMinutes: Boolean)
        val flipkartTargets = listOf(
            FkTarget(
                "Flipkart Coins PLP",
                "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold",
                false
            ),
            FkTarget(
                "Flipkart Minutes Instant Gold",
                "https://www.flipkart.com/minutes/search?q=gold+coin",
                true
            )
        )

        for (target in flipkartTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "flipkart.com",
                    "[Flipkart] Fetching ${target.name}..."
                )
                val resp1 = CronetNetworkClient.executeCronetRequest(target.url, pincode)
                if (resp1.status in 200..299) {
                    val parsed1 = FlipkartNativeParser.parse(resp1.body, "flipkart.com", bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("flipkart.com", parsed1.candidates, pincode)
                    discovered += targetDiscovered
                    valid += targetValid

                    val pagesToFetch = if (target.isMinutes) 1 else maxPages.coerceAtLeast(8)
                    if (pagesToFetch > 1) {
                        val semaphore = Semaphore(2)
                        coroutineScope {
                            (2..pagesToFetch).map { page ->
                                async {
                                    semaphore.withPermit {
                                        val pageParam = if (target.url.contains("?")) "&page=$page" else "?page=$page"
                                        val resp = CronetNetworkClient.executeCronetRequest("${target.url}$pageParam", pincode)
                                        if (resp.status in 200..299) {
                                            val p = FlipkartNativeParser.parse(resp.body, "flipkart.com", bullionRate24)
                                            val s = saveCandidates("flipkart.com", p.candidates, pincode)
                                            synchronized(this@NativeParallelRefreshEngine) {
                                                discovered += p.candidates.size
                                                valid += s
                                                targetDiscovered += p.candidates.size
                                                targetValid += s
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "flipkart.com",
                        "[Flipkart] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                    )
                } else {
                    lastError = "HTTP ${resp1.status}"
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        "flipkart.com",
                        "[Flipkart] ${target.name} returned HTTP ${resp1.status}"
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.e(tag, "Flipkart refresh error for ${target.name}: ${e.message}", e)
                activityRepository?.log(
                    RefreshLogSeverity.Error,
                    "flipkart.com",
                    "[Flipkart] ${target.name} failed: ${e.message}"
                )
            }
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("flipkart.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "flipkart.com",
                "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "flipkart.com",
                "Existing prices preserved: catalogue scan complete (${duration}ms)",
            )
        }
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
            val urlDescriptor = "Shopsy Gold Coins Category"
            val baseUrl = "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK"

            activityRepository?.log(
                RefreshLogSeverity.Info,
                "shopsy.in",
                "[Shopsy] Fetching $urlDescriptor (page 1)..."
            )

            val resp1 = CronetNetworkClient.executeCronetRequest(baseUrl, pincode)
            if (resp1.status in 200..299) {
                val parsed1 = FlipkartNativeParser.parse(resp1.body, "shopsy.in", bullionRate24)
                discovered += parsed1.candidates.size
                valid += saveCandidates("shopsy.in", parsed1.candidates, pincode)

                val pagesToFetch = maxPages.coerceAtLeast(8)
                if (pagesToFetch > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..pagesToFetch).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&page=$page", pincode)
                                    if (resp.status in 200..299) {
                                        val p = FlipkartNativeParser.parse(resp.body, "shopsy.in", bullionRate24)
                                        val s = saveCandidates("shopsy.in", p.candidates, pincode)
                                        synchronized(this@NativeParallelRefreshEngine) {
                                            discovered += p.candidates.size
                                            valid += s
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                val urlElapsed = System.currentTimeMillis() - start
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "shopsy.in",
                    "[Shopsy] $urlDescriptor: $discovered discovered, $valid valid saved (${pagesToFetch} pages, ${urlElapsed}ms)"
                )
            } else {
                lastError = "HTTP ${resp1.status}"
                activityRepository?.log(
                    RefreshLogSeverity.Warning,
                    "shopsy.in",
                    "[Shopsy] $urlDescriptor returned HTTP ${resp1.status}"
                )
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Shopsy refresh error: ${e.message}", e)
            activityRepository?.log(
                RefreshLogSeverity.Error,
                "shopsy.in",
                "[Shopsy] Refresh failed: ${e.message}"
            )
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("shopsy.in", discovered, valid, duration, true, lastError)
        onProgress(result)
        if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "shopsy.in",
                "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "shopsy.in",
                "Existing prices preserved: catalogue scan complete (${duration}ms)",
            )
        }
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
            val urlDescriptor = "Amazon Gold Coins & Bars (Popularity)"
            val baseUrl = "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR"

            activityRepository?.log(
                RefreshLogSeverity.Info,
                "amazon.in",
                "[Amazon] Fetching $urlDescriptor (page 1)..."
            )

            val resp1 = CronetNetworkClient.executeCronetRequest("$baseUrl&ref=sr_pg_1")
            if (resp1.status in 200..299) {
                val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                discovered += parsed1.candidates.size
                valid += saveCandidates("amazon.in", parsed1.candidates, null)

                val pagesToFetch = maxPages.coerceAtLeast(8)
                if (pagesToFetch > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..pagesToFetch).map { page ->
                            async {
                                semaphore.withPermit {
                                    val resp = CronetNetworkClient.executeCronetRequest("$baseUrl&page=$page&ref=sr_pg_$page")
                                    if (resp.status in 200..299) {
                                        val p = AmazonNativeParser.parse(resp.body, bullionRate24)
                                        val s = saveCandidates("amazon.in", p.candidates, null)
                                        synchronized(this@NativeParallelRefreshEngine) {
                                            discovered += p.candidates.size
                                            valid += s
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                val urlElapsed = System.currentTimeMillis() - start
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "amazon.in",
                    "[Amazon] $urlDescriptor: $discovered discovered, $valid valid saved (${pagesToFetch} pages, ${urlElapsed}ms)"
                )
            } else {
                lastError = "HTTP ${resp1.status}"
                activityRepository?.log(
                    RefreshLogSeverity.Warning,
                    "amazon.in",
                    "[Amazon] $urlDescriptor returned HTTP ${resp1.status}"
                )
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Amazon refresh error: ${e.message}", e)
            activityRepository?.log(
                RefreshLogSeverity.Error,
                "amazon.in",
                "[Amazon] Refresh failed: ${e.message}"
            )
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("amazon.in", discovered, valid, duration, true, lastError)
        onProgress(result)
        if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "amazon.in",
                "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "amazon.in",
                "Existing prices preserved: catalogue scan complete (${duration}ms)",
            )
        }
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
            val desktopHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            )

            val urlDescriptor = "Myntra Gold Coins PLP (window.__myx)"
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "myntra.com",
                "[Myntra] Fetching $urlDescriptor (page 1)..."
            )

            // Step 1: Query main HTML page with Desktop User-Agent (which embeds window.__myx)
            val pageResp = CronetNetworkClient.executeCronetWithHeaders("https://www.myntra.com/gold-coin?p=1", desktopHeaders)
            if (pageResp.status in 200..299 && pageResp.body.contains("window.__myx")) {
                val parsed = MyntraNativeParser.parse(pageResp.body, bullionRate24)
                discovered += parsed.candidates.size
                valid += saveCandidates("myntra.com", parsed.candidates, pincode)

                val totalCount = parsed.totalCount
                val totalPages = ((totalCount + 49) / 50).coerceAtMost(maxPages.coerceAtLeast(8))
                if (totalPages > 1) {
                    val semaphore = Semaphore(2)
                    coroutineScope {
                        (2..totalPages).map { page ->
                            async {
                                semaphore.withPermit {
                                    val r = CronetNetworkClient.executeCronetWithHeaders("https://www.myntra.com/gold-coin?p=$page", desktopHeaders)
                                    if (r.status in 200..299) {
                                        val p = MyntraNativeParser.parse(r.body, bullionRate24)
                                        val s = saveCandidates("myntra.com", p.candidates, pincode)
                                        synchronized(this@NativeParallelRefreshEngine) {
                                            discovered += p.candidates.size
                                            valid += s
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                val urlElapsed = System.currentTimeMillis() - start
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "myntra.com",
                    "[Myntra] $urlDescriptor: $discovered discovered, $valid valid saved (${totalPages} pages, ${urlElapsed}ms)"
                )
            } else {
                // Step 2: Gateway API fallback
                val gatewayHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "application/json",
                    "x-myntraweb" to "Yes",
                    "x-requested-with" to "browser",
                    "x-meta-app" to "channel=web",
                    "Referer" to "https://www.myntra.com/gold-coin",
                )
                val gatewayUrl = "https://www.myntra.com/gateway/v4/search/gold-coin?rows=50&o=0&p=1&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode"
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "myntra.com",
                    "[Myntra] Web page fallback -> Gateway API v4..."
                )
                val resp = CronetNetworkClient.executeCronetWithHeaders(gatewayUrl, gatewayHeaders)
                if (resp.status in 200..299) {
                    val parsed = MyntraNativeParser.parse(resp.body, bullionRate24)
                    discovered += parsed.candidates.size
                    valid += saveCandidates("myntra.com", parsed.candidates, pincode)
                    val urlElapsed = System.currentTimeMillis() - start
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "myntra.com",
                        "[Myntra] Gateway API: $discovered discovered, $valid valid saved (${urlElapsed}ms)"
                    )
                } else {
                    lastError = "Page HTTP ${pageResp.status}, Gateway HTTP ${resp.status}"
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        "myntra.com",
                        "[Myntra] Failed: Page HTTP ${pageResp.status}, Gateway HTTP ${resp.status}"
                    )
                }
            }
        } catch (e: Exception) {
            lastError = e.message
            Log.e(tag, "Myntra refresh error: ${e.message}", e)
            activityRepository?.log(
                RefreshLogSeverity.Error,
                "myntra.com",
                "[Myntra] Refresh failed: ${e.message}"
            )
        }

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("myntra.com", discovered, valid, duration, true, lastError)
        onProgress(result)
        if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "myntra.com",
                "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "myntra.com",
                "Existing prices preserved: catalogue scan complete (${duration}ms)",
            )
        }
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

                    val logStore = if (sourceId == "tan") "tanishq" else sourceId
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        logStore,
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
                    ?: candidate.retailerId.substringBefore('_').takeIf { it != candidate.retailerId }?.let { prefix ->
                        database.dao().productByRetailerId(store, prefix)
                    }
                    ?: if (store == "shopsy.in") {
                        database.dao().productByRetailerId("flipkart.com", candidate.retailerId)
                    } else null

                val entityId = existing?.id ?: UUID.randomUUID().toString()
                val targetStore = existing?.store ?: store
                val targetRetailerId = existing?.retailerId ?: candidate.retailerId

                val entity = ProductEntity(
                    id = entityId,
                    store = targetStore,
                    retailerId = targetRetailerId,
                    canonicalUrl = if (candidate.canonicalUrl.isNotBlank()) candidate.canonicalUrl else existing?.canonicalUrl.orEmpty(),
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
