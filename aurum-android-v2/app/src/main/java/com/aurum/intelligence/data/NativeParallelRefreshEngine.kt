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
import kotlinx.coroutines.delay
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
        var isBlocked = false

        data class AjioTarget(val name: String, val baseUrl: String)
        val ajioTargets = listOf(
            AjioTarget(
                "Category 8303 (Women Fine Jewellery / 24K Gold)",
                "https://www.ajio.com/api/category/8303?fields=SITE&pageSize=99&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29&gridColumns=3&platform=Desktop&store=ajio&advfilter=true&pincode=$pincode"
            ),
            AjioTarget(
                "Category 176606 (Jewellery Landing)",
                "https://www.ajio.com/api/category/83?pageSize=99&format=json&query=%3Arelevance%3Arelevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&fields=SITE&facets=relevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&gridColumns=3&platform=Desktop&store=ajio&curated=true&curatedid=jewellery-176606&advfilter=true&pincode=$pincode"
            ),
            AjioTarget(
                "Category 169379 (Girls Jewellery)",
                "https://www.ajio.com/api/category/83?pageSize=99&format=json&query=%3Arelevance%3Arelevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&fields=SITE&facets=relevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&gridColumns=3&platform=Desktop&store=ajio&curated=true&curatedid=girls-169379&advfilter=true&pincode=$pincode"
            ),
            AjioTarget(
                "Category 169373 (Boys Jewellery)",
                "https://www.ajio.com/api/category/83?pageSize=99&format=json&query=%3Arelevance%3Arelevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&fields=SITE&facets=relevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A22+Kt&gridColumns=3&platform=Desktop&store=ajio&curated=true&curatedid=boys-169373&advfilter=true&pincode=$pincode"
            )
        )

        val safetyCeiling = maxPages.coerceAtLeast(100)

        for (target in ajioTargets) {
            val urlStart = System.currentTimeMillis()
            var targetDiscovered = 0
            var targetValid = 0
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "ajio.com",
                    "[AJIO] DISCOVERING page=0 target=${target.name}..."
                )
                var resp0 = CronetNetworkClient.executeCronetApiRequest("${target.baseUrl}&currentPage=0", pincode)
                if (resp0.status == 403 || resp0.status == 429) {
                    delay(2000L) // Gentle backoff on rate limit
                    resp0 = CronetNetworkClient.executeCronetApiRequest("${target.baseUrl}&currentPage=0", pincode)
                }

                if (resp0.status == 403) {
                    lastError = "HTTP 403 / BLOCKED"
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        "ajio.com",
                        "[AJIO] HTTP status=403 state=BLOCKED destructiveReconciliation=false target=${target.name}"
                    )
                    continue
                }

                if (resp0.status in 200..299) {
                    val parsed0 = AjioNativeParser.parse(resp0.body, bullionRate24)
                    val p0Discovered = parsed0.candidates.size
                    val p0Saved = saveCandidates("ajio.com", parsed0.candidates, pincode)
                    targetDiscovered += p0Discovered
                    targetValid += p0Saved
                    discovered += p0Discovered
                    valid += p0Saved

                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "ajio.com",
                        "[AJIO] DISCOVERING page=0 HTTP status=200 discovered=$p0Discovered accepted=$p0Saved"
                    )

                    database.dao().insertRawPayload(
                        RawBridgePayloadEntity(
                            id = UUID.randomUUID().toString(),
                            store = "ajio_master_${target.name.replace(" ", "_")}_page_0",
                            receivedAt = System.currentTimeMillis(),
                            json = resp0.body
                        )
                    )

                    val totalPagesFromSource = parsed0.totalPages
                    val maxPageLimit = if (totalPagesFromSource > 0) minOf(totalPagesFromSource, safetyCeiling) else safetyCeiling

                    if (maxPageLimit > 1) {
                        for (page in 1 until maxPageLimit) {
                            delay(350L) // Respectful pacing prevents Akamai 403 rate-limiting
                            activityRepository?.log(
                                RefreshLogSeverity.Info,
                                "ajio.com",
                                "[AJIO] DISCOVERING page=$page target=${target.name}..."
                            )
                            var pResp = CronetNetworkClient.executeCronetApiRequest("${target.baseUrl}&currentPage=$page", pincode)
                            if (pResp.status == 403 || pResp.status == 429) {
                                delay(2000L)
                                pResp = CronetNetworkClient.executeCronetApiRequest("${target.baseUrl}&currentPage=$page", pincode)
                            }
                            if (pResp.status == 403) {
                                activityRepository?.log(
                                    RefreshLogSeverity.Warning,
                                    "ajio.com",
                                    "[AJIO] HTTP status=403 on page=$page for ${target.name} (end of query depth)"
                                )
                                break
                            }

                            if (pResp.status in 200..299) {
                                database.dao().insertRawPayload(
                                    RawBridgePayloadEntity(
                                        id = UUID.randomUUID().toString(),
                                        store = "ajio_master_${target.name.replace(" ", "_")}_page_$page",
                                        receivedAt = System.currentTimeMillis(),
                                        json = pResp.body
                                    )
                                )
                                val pParsed = AjioNativeParser.parse(pResp.body, bullionRate24)
                                val pDiscovered = pParsed.candidates.size
                                val pSaved = saveCandidates("ajio.com", pParsed.candidates, pincode)

                                targetDiscovered += pDiscovered
                                targetValid += pSaved
                                discovered += pDiscovered
                                valid += pSaved

                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "ajio.com",
                                    "[AJIO] DISCOVERING page=$page HTTP status=200 discovered=$pDiscovered accepted=$pSaved"
                                )

                                if (pDiscovered == 0) {
                                    break
                                }
                            } else {
                                lastError = "HTTP ${pResp.status}"
                                activityRepository?.log(
                                    RefreshLogSeverity.Warning,
                                    "ajio.com",
                                    "[AJIO] page=$page returned HTTP ${pResp.status}"
                                )
                                break
                            }
                        }
                    }

                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "ajio.com",
                        "[AJIO] Target ${target.name} complete: $targetDiscovered discovered, $targetValid valid (${urlElapsed}ms)"
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

        // Sequential PDP verification & enrichment for remaining unrefreshed/stale items
        val pdpUpdated = refreshStorePdp("ajio.com", start, pincode)
        valid += pdpUpdated

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress("ajio.com", discovered, valid, duration, true, lastError)
        onProgress(result)

        if (isBlocked) {
            activityRepository?.log(
                RefreshLogSeverity.Warning,
                "ajio.com",
                "[AJIO] BLOCKED / PARTIAL_SUCCESS: Existing catalogue preserved without destructive reconciliation (${duration}ms)",
            )
        } else if (valid > 0 || discovered > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "ajio.com",
                "[AJIO] SUCCESS: totalDiscovered=$discovered, totalAccepted=$valid ingested (${duration}ms)",
            )
        } else {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                "ajio.com",
                "[AJIO] Existing prices preserved: catalogue scan complete (${duration}ms)",
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
                "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold&pinCode=$pincode",
                false
            ),
            FkTarget(
                "Flipkart Gold Coins Search",
                "https://www.flipkart.com/search?q=gold+coin&marketplace=FLIPKART&pinCode=$pincode",
                false
            ),
            FkTarget(
                "Flipkart Gold Bars Search",
                "https://www.flipkart.com/search?q=gold+bar&marketplace=FLIPKART&pinCode=$pincode",
                false
            ),
            FkTarget(
                "Flipkart Minutes Instant Gold",
                "https://www.flipkart.com/minutes/search?q=gold+coin&pinCode=$pincode",
                true
            )
        )

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        )

        for (target in flipkartTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "flipkart.com",
                    "[Flipkart] Fetching ${target.name} (page 1)..."
                )
                val resp1 = CronetNetworkClient.executeCronetWithHeaders(target.url, desktopHeaders)
                if (resp1.status in 200..299) {
                    val parsed1 = FlipkartNativeParser.parse(resp1.body, "flipkart.com", bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("flipkart.com", parsed1.candidates, pincode)
                    discovered += targetDiscovered
                    valid += targetValid

                    val pageCap = if (target.isMinutes) 1 else 100
                    if (pageCap > 1 && parsed1.candidates.isNotEmpty()) {
                        for (page in 2..pageCap) {
                            delay(200L)
                            val pageParam = if (target.url.contains("?")) "&page=$page" else "?page=$page"
                            val resp = CronetNetworkClient.executeCronetWithHeaders("${target.url}$pageParam", desktopHeaders)
                            if (resp.status in 200..299) {
                                val p = FlipkartNativeParser.parse(resp.body, "flipkart.com", bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("flipkart.com", p.candidates, pincode)
                                discovered += p.candidates.size
                                valid += s
                                targetDiscovered += p.candidates.size
                                targetValid += s
                            } else {
                                break
                            }
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

        // Sequential PDP verification & enrichment for remaining unrefreshed/stale items
        val pdpUpdated = refreshStorePdp("flipkart.com", start, pincode)
        valid += pdpUpdated

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

        data class ShopsyTarget(val name: String, val url: String)
        val shopsyTargets = listOf(
            ShopsyTarget(
                "Shopsy Gold Coins Category",
                "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK&pinCode=$pincode"
            ),
            ShopsyTarget(
                "Shopsy Gold Coins Search",
                "https://www.shopsy.in/search?q=gold+coin&marketplace=FLIPKART&pinCode=$pincode"
            ),
            ShopsyTarget(
                "Shopsy Gold Bars Search",
                "https://www.shopsy.in/search?q=gold+bar&marketplace=FLIPKART&pinCode=$pincode"
            )
        )

        for (target in shopsyTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "shopsy.in",
                    "[Shopsy] Fetching ${target.name} (page 1)..."
                )
                val resp1 = CronetNetworkClient.executeCronetRequest(target.url, pincode)
                if (resp1.status in 200..299) {
                    val parsed1 = FlipkartNativeParser.parse(resp1.body, "shopsy.in", bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("shopsy.in", parsed1.candidates, pincode)
                    discovered += targetDiscovered
                    valid += targetValid

                    if (parsed1.candidates.isNotEmpty()) {
                        for (page in 2..100) {
                            delay(200L)
                            val pageParam = if (target.url.contains("?")) "&page=$page" else "?page=$page"
                            val resp = CronetNetworkClient.executeCronetRequest("${target.url}$pageParam", pincode)
                            if (resp.status in 200..299) {
                                val p = FlipkartNativeParser.parse(resp.body, "shopsy.in", bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("shopsy.in", p.candidates, pincode)
                                discovered += p.candidates.size
                                valid += s
                                targetDiscovered += p.candidates.size
                                targetValid += s
                            } else {
                                break
                            }
                        }
                    }
                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "shopsy.in",
                        "[Shopsy] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                    )
                } else {
                    lastError = "HTTP ${resp1.status}"
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        "shopsy.in",
                        "[Shopsy] ${target.name} returned HTTP ${resp1.status}"
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.e(tag, "Shopsy refresh error for ${target.name}: ${e.message}", e)
                activityRepository?.log(
                    RefreshLogSeverity.Error,
                    "shopsy.in",
                    "[Shopsy] ${target.name} failed: ${e.message}"
                )
            }
        }

        // Sequential PDP verification & enrichment for remaining unrefreshed/stale items
        val pdpUpdated = refreshStorePdp("shopsy.in", start, pincode)
        valid += pdpUpdated

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

        data class AmazonTarget(val name: String, val baseUrl: String)
        val amazonTargets = listOf(
            AmazonTarget(
                "Amazon Gold Coins & Bars (Popularity)",
                "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR"
            ),
            AmazonTarget(
                "Amazon Gold Coin Search",
                "https://www.amazon.in/s?k=gold+coin&i=jewelry"
            ),
            AmazonTarget(
                "Amazon Gold Bar Search",
                "https://www.amazon.in/s?k=gold+bar&i=jewelry"
            )
        )

        for (target in amazonTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "amazon.in",
                    "[Amazon] Fetching ${target.name} (page 1)..."
                )
                val resp1 = CronetNetworkClient.executeCronetRequest("${target.baseUrl}&ref=sr_pg_1")
                if (resp1.status in 200..299) {
                    val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("amazon.in", parsed1.candidates, null)
                    discovered += targetDiscovered
                    valid += targetValid

                    if (parsed1.candidates.isNotEmpty()) {
                        for (page in 2..100) {
                            delay(350L) // Gentle pacing avoids Amazon bot detection
                            val resp = CronetNetworkClient.executeCronetRequest("${target.baseUrl}&page=$page&ref=sr_pg_$page")
                            if (resp.status in 200..299) {
                                val p = AmazonNativeParser.parse(resp.body, bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("amazon.in", p.candidates, null)
                                discovered += p.candidates.size
                                valid += s
                                targetDiscovered += p.candidates.size
                                targetValid += s
                            } else {
                                break
                            }
                        }
                    }
                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "amazon.in",
                        "[Amazon] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                    )
                } else {
                    lastError = "HTTP ${resp1.status}"
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        "amazon.in",
                        "[Amazon] ${target.name} returned HTTP ${resp1.status}"
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.e(tag, "Amazon refresh error for ${target.name}: ${e.message}", e)
                activityRepository?.log(
                    RefreshLogSeverity.Error,
                    "amazon.in",
                    "[Amazon] ${target.name} failed: ${e.message}"
                )
            }
        }

        // Sequential PDP verification & enrichment for remaining unrefreshed/stale items
        val pdpUpdated = refreshStorePdp("amazon.in", start, null)
        valid += pdpUpdated

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

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        )

        data class MyntraTarget(val name: String, val slug: String)
        val myntraTargets = listOf(
            MyntraTarget("Myntra Gold Coins", "gold-coin"),
            MyntraTarget("Myntra Gold Bars", "gold-bar")
        )

        for (target in myntraTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "myntra.com",
                    "[Myntra] Fetching ${target.name} (page 1)..."
                )
                var targetDiscovered = 0
                var targetValid = 0

                val pageResp = CronetNetworkClient.executeCronetWithHeaders("https://www.myntra.com/${target.slug}?p=1", desktopHeaders)
                if (pageResp.status in 200..299 && pageResp.body.contains("window.__myx")) {
                    val parsed = MyntraNativeParser.parse(pageResp.body, bullionRate24)
                    val s0 = saveCandidates("myntra.com", parsed.candidates, pincode)
                    targetDiscovered += parsed.candidates.size
                    targetValid += s0
                    discovered += parsed.candidates.size
                    valid += s0

                    if (parsed.candidates.isNotEmpty()) {
                        for (page in 2..100) {
                            delay(200L)
                            val r = CronetNetworkClient.executeCronetWithHeaders("https://www.myntra.com/${target.slug}?p=$page", desktopHeaders)
                            if (r.status in 200..299) {
                                val p = MyntraNativeParser.parse(r.body, bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("myntra.com", p.candidates, pincode)
                                discovered += p.candidates.size
                                valid += s
                                targetDiscovered += p.candidates.size
                                targetValid += s
                            } else {
                                break
                            }
                        }
                    }
                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "myntra.com",
                        "[Myntra] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                    )
                } else {
                    // Gateway API fallback with dynamic pagination
                    val gatewayHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Accept" to "application/json",
                        "x-myntraweb" to "Yes",
                        "x-requested-with" to "browser",
                        "x-meta-app" to "channel=web",
                        "Referer" to "https://www.myntra.com/${target.slug}",
                    )
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= 100) {
                        delay(200L)
                        val offset = (page - 1) * 50
                        val gatewayUrl = "https://www.myntra.com/gateway/v4/search/${target.slug}?rows=50&o=$offset&p=$page&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode"
                        val resp = CronetNetworkClient.executeCronetWithHeaders(gatewayUrl, gatewayHeaders)
                        if (resp.status in 200..299) {
                            val parsed = MyntraNativeParser.parse(resp.body, bullionRate24)
                            val s = saveCandidates("myntra.com", parsed.candidates, pincode)
                            discovered += parsed.candidates.size
                            valid += s
                            targetDiscovered += parsed.candidates.size
                            targetValid += s
                            if (parsed.candidates.isEmpty() || (parsed.totalCount > 0 && page * 50 >= parsed.totalCount)) {
                                hasMore = false
                            }
                        } else {
                            hasMore = false
                        }
                        page++
                    }
                    val urlElapsed = System.currentTimeMillis() - urlStart
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "myntra.com",
                        "[Myntra] ${target.name} Gateway API: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.e(tag, "Myntra refresh error for ${target.name}: ${e.message}", e)
                activityRepository?.log(
                    RefreshLogSeverity.Error,
                    "myntra.com",
                    "[Myntra] ${target.name} failed: ${e.message}"
                )
            }
        }

        // Sequential PDP verification & enrichment for remaining unrefreshed/stale items
        val pdpUpdated = refreshStorePdp("myntra.com", start, pincode)
        valid += pdpUpdated

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
                val cleanRetailerId = candidate.retailerId.substringBefore('_')
                val existing = database.dao().productByRetailerId(store, candidate.retailerId)
                    ?: database.dao().productByRetailerId(store, cleanRetailerId)
                    ?: (if (candidate.canonicalUrl.isNotBlank()) database.dao().productByCanonicalUrl(candidate.canonicalUrl) else null)
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
                    runCatching {
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
                }

                validSaved++
            } catch (e: Exception) {
                Log.w(tag, "Failed to save product ${candidate.retailerId}: ${e.message}")
            }
        }

        return validSaved
    }

    // =========================================================================
    // SEQUENTIAL PDP VERIFICATION & ENRICHMENT
    // =========================================================================
    private suspend fun refreshStorePdp(
        store: String,
        startedAt: Long,
        pincode: String?,
    ): Int {
        val unrefreshed = database.dao().allProducts().filter { p ->
            p.store == store &&
                p.checkedAt < startedAt &&
                p.status != "unavailable"
        }
        if (unrefreshed.isEmpty()) return 0

        activityRepository?.log(
            RefreshLogSeverity.Info,
            store,
            "[$store] Starting PDP verification for ${unrefreshed.size} unrefreshed/stale items..."
        )

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        )
        val gatewayHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "x-myntraweb" to "Yes",
            "x-requested-with" to "browser",
            "x-meta-app" to "channel=web",
        )

        var pdpUpdated = 0
        var pdpUnavailable = 0
        var abortPdp = false

        for ((idx, product) in unrefreshed.withIndex()) {
            if (abortPdp) break
            delay(if (store == "amazon.in") 350L else 250L)

            val endpoint = when (store) {
                "ajio.com" -> {
                    val cleanId = product.retailerId.substringBefore('_')
                    "https://www.ajio.com/api/p/$cleanId"
                }
                "myntra.com", "amazon.in", "flipkart.com", "shopsy.in" -> product.canonicalUrl.takeIf { it.isNotBlank() }
                else -> null
            } ?: continue

            try {
                val response = when (store) {
                    "ajio.com" -> CronetNetworkClient.executeCronetApiRequest(endpoint, pincode ?: "560048")
                    else -> CronetNetworkClient.executeCronetWithHeaders(endpoint, desktopHeaders)
                }

                if (response.status == 403) {
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        store,
                        "[$store] PDP verification hit 403 on item ${idx + 1}/${unrefreshed.size} (${product.retailerId}). Halting PDP to preserve rate limit."
                    )
                    abortPdp = true
                    break
                }

                val now = System.currentTimeMillis()
                when (val lookup = ProductLookup.parse(store, response.status, response.body, endpoint)) {
                    is ProductLookup.Available -> {
                        val updatedProduct = product.copy(
                            name = lookup.name ?: product.name,
                            brand = lookup.brand ?: product.brand,
                            price = lookup.price,
                            couponPrice = lookup.couponPrice ?: product.couponPrice,
                            grams = lookup.grams ?: product.grams,
                            weightConfidence = lookup.weightConfidence,
                            status = "live",
                            refreshMethod = lookup.refreshMethod,
                            checkedAt = now,
                            lastLiveAt = now,
                            deliverable = true,
                            isBlinkDeal = lookup.isBlinkDeal,
                            blinkDealPrice = lookup.blinkDealPrice ?: product.blinkDealPrice,
                        )
                        database.dao().upsertProduct(updatedProduct)

                        if (product.price != lookup.price || product.couponPrice != lookup.couponPrice) {
                            runCatching {
                                database.dao().insertPriceHistory(
                                    ProductPriceHistoryEntity(
                                        productId = product.id,
                                        price = lookup.price,
                                        couponPrice = lookup.couponPrice,
                                        checkedAt = now,
                                    )
                                )
                            }
                        }
                        pdpUpdated++
                    }
                    is ProductLookup.Unavailable -> {
                        val updatedProduct = product.copy(
                            price = lookup.price ?: product.price,
                            status = "unavailable",
                            checkedAt = now,
                            deliverable = false,
                        )
                        database.dao().upsertProduct(updatedProduct)
                        pdpUnavailable++
                    }
                    ProductLookup.Unknown -> {
                        // Preservative: keep existing product intact
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "[$store] PDP error for ${product.retailerId}: ${e.message}")
            }
        }

        activityRepository?.log(
            RefreshLogSeverity.Info,
            store,
            "[$store] PDP sweep complete: $pdpUpdated live updated, $pdpUnavailable marked unavailable"
        )
        return pdpUpdated
    }
}
