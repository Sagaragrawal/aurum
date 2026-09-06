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
    val plpRequests: Int = 0,
    val pdpRequests: Int = 0,
    val pdpSuccessful: Int = 0,
    val pdpFailed: Int = 0,
    val pdpRequired: Int = 0,
    val plpProcessedFully: Int = 0,
)

data class PdpRefreshResult(
    val requests: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    val required: Int = 0,
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
    private val internalDatabase: AurumInternalDatabase? = null,
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
        val distinctPids = mutableSetOf<String>()
        var plpRequests = 0

        val config = ScraperConfigProvider.get()
        val ajioTargets = config.ajioTargets
        val safetyCeiling = maxPages.coerceAtLeast(config.limits.maxPagesPerStore)

        for (target in ajioTargets) {
            val urlStart = System.currentTimeMillis()
            var targetDiscovered = 0
            var targetValid = 0
            val targetBaseUrl = if (!target.url.contains("pincode=")) "${target.url}&pincode=$pincode" else target.url
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "ajio.com",
                    "[AJIO] DISCOVERING page=0 target=${target.name}..."
                )
                plpRequests++
                var resp0 = CronetNetworkClient.executeCronetApiRequest("${targetBaseUrl}&currentPage=0", pincode)
                if (resp0.status == 403 || resp0.status == 429) {
                    delay(config.delays.ajioRateLimitBackoffMs)
                    plpRequests++
                    resp0 = CronetNetworkClient.executeCronetApiRequest("${targetBaseUrl}&currentPage=0", pincode)
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
                    val p0Saved = saveCandidates("ajio.com", parsed0.candidates, pincode, distinctPids)
                    targetDiscovered += p0Discovered
                    targetValid += p0Saved
                    discovered += p0Discovered
                    valid += p0Saved

                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "ajio.com",
                        "[AJIO] DISCOVERING page=0 HTTP status=200 discovered=$p0Discovered accepted=$p0Saved totalAvailable=${parsed0.totalResults}"
                    )

                    recordRawPayload(
                        id = UUID.randomUUID().toString(),
                        store = "ajio_master_${target.name.replace(" ", "_")}_page_0",
                        json = resp0.body,
                    )

                    val totalPagesFromSource = parsed0.totalPages
                    val maxPageLimit = if (totalPagesFromSource > 0) minOf(totalPagesFromSource, safetyCeiling) else safetyCeiling

                    if (maxPageLimit > 1) {
                        for (page in 1 until maxPageLimit) {
                            delay(config.delays.ajioPageDelayMs)
                            activityRepository?.log(
                                RefreshLogSeverity.Info,
                                "ajio.com",
                                "[AJIO] DISCOVERING page=$page target=${target.name}..."
                            )
                            plpRequests++
                            var pResp = CronetNetworkClient.executeCronetApiRequest("${targetBaseUrl}&currentPage=$page", pincode)
                            if (pResp.status == 403 || pResp.status == 429) {
                                delay(config.delays.ajioRateLimitBackoffMs)
                                plpRequests++
                                pResp = CronetNetworkClient.executeCronetApiRequest("${targetBaseUrl}&currentPage=$page", pincode)
                            }
                            if (pResp.status == 403) {
                                activityRepository?.log(
                                    RefreshLogSeverity.Warning,
                                    "ajio.com",
                                    "[AJIO] HTTP status=403 on page=$page for ${target.name} (reached rate limit/depth ceiling). Preserving all $valid accepted products."
                                )
                                break
                            }

                            if (pResp.status in 200..299) {
                                recordRawPayload(
                                    id = UUID.randomUUID().toString(),
                                    store = "ajio_master_${target.name.replace(" ", "_")}_page_$page",
                                    json = pResp.body,
                                )
                                val pParsed = AjioNativeParser.parse(pResp.body, bullionRate24)
                                val pDiscovered = pParsed.candidates.size
                                val pSaved = saveCandidates("ajio.com", pParsed.candidates, pincode, distinctPids)

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
        val pdpResult = refreshStorePdp("ajio.com", start, pincode)
        valid += pdpResult.successful

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress(
            store = "ajio.com",
            itemsDiscovered = discovered,
            itemsValid = valid,
            durationMs = duration,
            isComplete = true,
            error = lastError,
            plpRequests = plpRequests,
            pdpRequests = pdpResult.requests,
            pdpSuccessful = pdpResult.successful,
            pdpFailed = pdpResult.failed,
            pdpRequired = pdpResult.required,
            plpProcessedFully = valid - pdpResult.successful,
        )
        onProgress(result)
        internalDatabase?.dao()?.insertExecutionMetrics(
            ScraperExecutionMetricsEntity(
                timestamp = System.currentTimeMillis(),
                store = "ajio.com",
                plpRequests = plpRequests,
                plpProductsDiscovered = discovered,
                pdpRequests = pdpResult.requests,
                pdpSuccessful = pdpResult.successful,
                pdpFailed = pdpResult.failed,
                pdpRequired = pdpResult.required,
                plpProcessedFully = valid - pdpResult.successful,
                is403Encountered = isBlocked || (pdpResult.failed > 0 && lastError?.contains("403") == true),
                durationMs = duration,
            )
        )

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
        val distinctPids = mutableSetOf<String>()
        var plpRequests = 0

        val config = ScraperConfigProvider.get()
        val flipkartTargets = config.flipkartTargets

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        )

        for (target in flipkartTargets) {
            val urlStart = System.currentTimeMillis()
            val targetUrl = if (!target.url.contains("pinCode=")) {
                val sep = if (target.url.contains("?")) "&" else "?"
                "${target.url}${sep}pinCode=$pincode"
            } else target.url
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "flipkart.com",
                    "[Flipkart] Fetching ${target.name} (page 1)..."
                )
                plpRequests++
                val resp1 = CronetNetworkClient.executeCronetWithHeaders(targetUrl, desktopHeaders)
                if (resp1.status in 200..299) {
                    val parsed1 = FlipkartNativeParser.parse(resp1.body, "flipkart.com", bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("flipkart.com", parsed1.candidates, pincode, distinctPids)
                    discovered += targetDiscovered
                    valid += targetValid

                    val pageCap = if (target.isMinutes) 1 else config.limits.maxPagesPerStore
                    if (pageCap > 1 && parsed1.candidates.isNotEmpty()) {
                        for (page in 2..pageCap) {
                            delay(config.delays.flipkartPageDelayMs)
                            val pageParam = if (targetUrl.contains("?")) "&page=$page" else "?page=$page"
                            plpRequests++
                            val resp = CronetNetworkClient.executeCronetWithHeaders("${targetUrl}$pageParam", desktopHeaders)
                            if (resp.status in 200..299) {
                                val p = FlipkartNativeParser.parse(resp.body, "flipkart.com", bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("flipkart.com", p.candidates, pincode, distinctPids)
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
        val pdpResult = refreshStorePdp("flipkart.com", start, pincode)
        valid += pdpResult.successful

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress(
            store = "flipkart.com",
            itemsDiscovered = discovered,
            itemsValid = valid,
            durationMs = duration,
            isComplete = true,
            error = lastError,
            plpRequests = plpRequests,
            pdpRequests = pdpResult.requests,
            pdpSuccessful = pdpResult.successful,
            pdpFailed = pdpResult.failed,
            pdpRequired = pdpResult.required,
            plpProcessedFully = valid - pdpResult.successful,
        )
        onProgress(result)
        internalDatabase?.dao()?.insertExecutionMetrics(
            ScraperExecutionMetricsEntity(
                timestamp = System.currentTimeMillis(),
                store = "flipkart.com",
                plpRequests = plpRequests,
                plpProductsDiscovered = discovered,
                pdpRequests = pdpResult.requests,
                pdpSuccessful = pdpResult.successful,
                pdpFailed = pdpResult.failed,
                pdpRequired = pdpResult.required,
                plpProcessedFully = valid - pdpResult.successful,
                is403Encountered = pdpResult.failed > 0 && lastError?.contains("403") == true,
                durationMs = duration,
            )
        )
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
        val distinctPids = mutableSetOf<String>()
        var plpRequests = 0

        val config = ScraperConfigProvider.get()
        val shopsyTargets = config.shopsyTargets

        for (target in shopsyTargets) {
            val urlStart = System.currentTimeMillis()
            val targetUrl = if (!target.url.contains("pinCode=")) {
                val sep = if (target.url.contains("?")) "&" else "?"
                "${target.url}${sep}pinCode=$pincode"
            } else target.url
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "shopsy.in",
                    "[Shopsy] Fetching ${target.name} (page 1)..."
                )
                plpRequests++
                val resp1 = CronetNetworkClient.executeCronetRequest(targetUrl, pincode)
                if (resp1.status in 200..299) {
                    val parsed1 = FlipkartNativeParser.parse(resp1.body, "shopsy.in", bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("shopsy.in", parsed1.candidates, pincode, distinctPids)
                    discovered += targetDiscovered
                    valid += targetValid

                    if (parsed1.candidates.isNotEmpty()) {
                        for (page in 2..config.limits.maxPagesPerStore) {
                            delay(config.delays.shopsyPageDelayMs)
                            val pageParam = if (targetUrl.contains("?")) "&page=$page" else "?page=$page"
                            plpRequests++
                            val resp = CronetNetworkClient.executeCronetRequest("${targetUrl}$pageParam", pincode)
                            if (resp.status in 200..299) {
                                val p = FlipkartNativeParser.parse(resp.body, "shopsy.in", bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("shopsy.in", p.candidates, pincode, distinctPids)
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
        val pdpResult = refreshStorePdp("shopsy.in", start, pincode)
        valid += pdpResult.successful

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress(
            store = "shopsy.in",
            itemsDiscovered = discovered,
            itemsValid = valid,
            durationMs = duration,
            isComplete = true,
            error = lastError,
            plpRequests = plpRequests,
            pdpRequests = pdpResult.requests,
            pdpSuccessful = pdpResult.successful,
            pdpFailed = pdpResult.failed,
            pdpRequired = pdpResult.required,
            plpProcessedFully = valid - pdpResult.successful,
        )
        onProgress(result)
        internalDatabase?.dao()?.insertExecutionMetrics(
            ScraperExecutionMetricsEntity(
                timestamp = System.currentTimeMillis(),
                store = "shopsy.in",
                plpRequests = plpRequests,
                plpProductsDiscovered = discovered,
                pdpRequests = pdpResult.requests,
                pdpSuccessful = pdpResult.successful,
                pdpFailed = pdpResult.failed,
                pdpRequired = pdpResult.required,
                plpProcessedFully = valid - pdpResult.successful,
                is403Encountered = pdpResult.failed > 0 && lastError?.contains("403") == true,
                durationMs = duration,
            )
        )
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
        val distinctPids = mutableSetOf<String>()
        var plpRequests = 0

        val config = ScraperConfigProvider.get()
        val amazonTargets = config.amazonTargets

        for (target in amazonTargets) {
            val urlStart = System.currentTimeMillis()
            try {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    "amazon.in",
                    "[Amazon] Fetching ${target.name} (page 1)..."
                )
                val page1Url = if (target.url.contains("ref=")) target.url else "${target.url}&ref=sr_pg_1"
                plpRequests++
                val resp1 = CronetNetworkClient.executeCronetRequest(page1Url)
                if (resp1.status in 200..299) {
                    val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                    var targetDiscovered = parsed1.candidates.size
                    var targetValid = saveCandidates("amazon.in", parsed1.candidates, null, distinctPids)
                    discovered += targetDiscovered
                    valid += targetValid

                    if (parsed1.candidates.isNotEmpty()) {
                        for (page in 2..config.limits.maxPagesPerStore) {
                            delay(config.delays.amazonPageDelayMs) // Gentle pacing avoids Amazon bot detection
                            plpRequests++
                            val resp = CronetNetworkClient.executeCronetRequest("${target.url}&page=$page&ref=sr_pg_$page")
                            if (resp.status in 200..299) {
                                val p = AmazonNativeParser.parse(resp.body, bullionRate24)
                                if (p.candidates.isEmpty()) break
                                val s = saveCandidates("amazon.in", p.candidates, null, distinctPids)
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
        val pdpResult = refreshStorePdp("amazon.in", start, null)
        valid += pdpResult.successful

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress(
            store = "amazon.in",
            itemsDiscovered = discovered,
            itemsValid = valid,
            durationMs = duration,
            isComplete = true,
            error = lastError,
            plpRequests = plpRequests,
            pdpRequests = pdpResult.requests,
            pdpSuccessful = pdpResult.successful,
            pdpFailed = pdpResult.failed,
            pdpRequired = pdpResult.required,
            plpProcessedFully = valid - pdpResult.successful,
        )
        onProgress(result)
        internalDatabase?.dao()?.insertExecutionMetrics(
            ScraperExecutionMetricsEntity(
                timestamp = System.currentTimeMillis(),
                store = "amazon.in",
                plpRequests = plpRequests,
                plpProductsDiscovered = discovered,
                pdpRequests = pdpResult.requests,
                pdpSuccessful = pdpResult.successful,
                pdpFailed = pdpResult.failed,
                pdpRequired = pdpResult.required,
                plpProcessedFully = valid - pdpResult.successful,
                is403Encountered = pdpResult.failed > 0 && lastError?.contains("403") == true,
                durationMs = duration,
            )
        )
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
        val distinctPids = mutableSetOf<String>()
        var plpRequests = 0

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        )

        val config = ScraperConfigProvider.get()
        val myntraTargets = config.myntraTargets

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

                val page1Sep = if (target.url.contains("?")) "&" else "?"
                val page1Url = "${target.url}${page1Sep}p=1"
                plpRequests++
                val pageResp = CronetNetworkClient.executeCronetWithHeaders(page1Url, desktopHeaders)
                if (pageResp.status in 200..299 && pageResp.body.contains("window.__myx")) {
                    val parsed = MyntraNativeParser.parse(pageResp.body, bullionRate24)
                    val s0 = saveCandidates("myntra.com", parsed.candidates, pincode, distinctPids)
                    targetDiscovered += parsed.candidates.size
                    targetValid += s0
                    discovered += parsed.candidates.size
                    valid += s0

                    val seenIds = parsed.candidates.map { it.retailerId }.toMutableSet()
                    if (parsed.candidates.isNotEmpty()) {
                        for (page in 2..config.limits.maxPagesPerStore) {
                            delay(config.delays.myntraWebPageDelayMs)
                            val pageSep = if (target.url.contains("?")) "&" else "?"
                            plpRequests++
                            val r = CronetNetworkClient.executeCronetWithHeaders("${target.url}${pageSep}p=$page", desktopHeaders)
                            if (r.status in 200..299) {
                                val p = MyntraNativeParser.parse(r.body, bullionRate24)
                                val newCandidates = p.candidates.filter { it.retailerId !in seenIds }
                                if (newCandidates.isEmpty()) {
                                    break
                                }
                                newCandidates.forEach { seenIds.add(it.retailerId) }
                                val s = saveCandidates("myntra.com", newCandidates, pincode, distinctPids)
                                discovered += newCandidates.size
                                valid += s
                                targetDiscovered += newCandidates.size
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
                    // Gateway API fallback with dynamic pagination and category filter
                    val gatewayHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Accept" to "application/json",
                        "x-myntraweb" to "Yes",
                        "x-requested-with" to "browser",
                        "x-meta-app" to "channel=web",
                        "Referer" to target.url,
                    )
                    val pageSize = config.limits.myntraPageSize
                    val filterParam = if (target.filterQuery.isNotBlank()) "&${target.filterQuery}" else ""
                    var page = 1
                    var hasMore = true
                    while (hasMore && page <= config.limits.maxPagesPerStore) {
                        delay(config.delays.myntraApiPageDelayMs)
                        val offset = (page - 1) * pageSize
                        val gatewayUrl = "https://www.myntra.com/gateway/v4/search/${target.slug}?rows=$pageSize&o=$offset&p=$page&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode$filterParam"
                        plpRequests++
                        val resp = CronetNetworkClient.executeCronetWithHeaders(gatewayUrl, gatewayHeaders)
                        if (resp.status in 200..299) {
                            val parsed = MyntraNativeParser.parse(resp.body, bullionRate24)
                            val s = saveCandidates("myntra.com", parsed.candidates, pincode, distinctPids)
                            discovered += parsed.candidates.size
                            valid += s
                            targetDiscovered += parsed.candidates.size
                            targetValid += s
                            if (parsed.candidates.isEmpty() || (parsed.totalCount > 0 && page * pageSize >= parsed.totalCount)) {
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
        val pdpResult = refreshStorePdp("myntra.com", start, pincode)
        valid += pdpResult.successful

        val duration = System.currentTimeMillis() - start
        val result = StoreRefreshProgress(
            store = "myntra.com",
            itemsDiscovered = discovered,
            itemsValid = valid,
            durationMs = duration,
            isComplete = true,
            error = lastError,
            plpRequests = plpRequests,
            pdpRequests = pdpResult.requests,
            pdpSuccessful = pdpResult.successful,
            pdpFailed = pdpResult.failed,
            pdpRequired = pdpResult.required,
            plpProcessedFully = valid - pdpResult.successful,
        )
        onProgress(result)
        internalDatabase?.dao()?.insertExecutionMetrics(
            ScraperExecutionMetricsEntity(
                timestamp = System.currentTimeMillis(),
                store = "myntra.com",
                plpRequests = plpRequests,
                plpProductsDiscovered = discovered,
                pdpRequests = pdpResult.requests,
                pdpSuccessful = pdpResult.successful,
                pdpFailed = pdpResult.failed,
                pdpRequired = pdpResult.required,
                plpProcessedFully = valid - pdpResult.successful,
                is403Encountered = pdpResult.failed > 0 && lastError?.contains("403") == true,
                durationMs = duration,
            )
        )
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
    // =========================================================================
    // BULLION ENGINE (Parallelized from Config)
    // =========================================================================
    private suspend fun refreshBullion(): Map<String, Double?> = coroutineScope {
        val config = ScraperConfigProvider.get()
        val deferredList = config.bullionTargets.map { target ->
            async {
                target.sourceId to refreshBullionSource(target.sourceId, target.url, target.label)
            }
        }
        deferredList.awaitAll().toMap()
    }

    private suspend fun refreshBullionSource(sourceId: String, url: String, label: String): Double? {
        val now = System.currentTimeMillis()
        return try {
            val response = CronetNetworkClient.executeCronetRequest(url)
            if (response.status in 200..299) {
                val parsed = BullionNativeParser.parse(sourceId, response.body)
                if (parsed.price24 != null && parsed.price24 > 1000) {
                    val existing = database.dao().bullionSourceById(sourceId)

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
        distinctPids: MutableSet<String>? = null,
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

                // STRICT PRE-INSERTION VALIDATION LAYER
                val validation = Product24KValidator.validate(
                    name = candidate.name ?: existing?.name ?: candidate.retailerId,
                    store = store,
                    karat = candidate.karat ?: existing?.karat,
                    purity = candidate.purity ?: existing?.purity,
                    price = candidate.price,
                    grams = candidate.grams ?: existing?.grams,
                    brand = candidate.brand ?: existing?.brand,
                    canonicalUrl = candidate.canonicalUrl,
                    retailerId = candidate.retailerId,
                )

                if (!validation.isValid) {
                    if (existing != null) {
                        database.dao().deleteProduct(existing.id)
                    }
                    continue
                }

                val entityId = existing?.id ?: UUID.randomUUID().toString()
                val targetStore = existing?.store ?: store
                val targetRetailerId = existing?.retailerId ?: candidate.retailerId

                val entity = ProductEntity(
                    id = entityId,
                    store = targetStore,
                    retailerId = targetRetailerId,
                    canonicalUrl = if (candidate.canonicalUrl.isNotBlank()) candidate.canonicalUrl else existing?.canonicalUrl.orEmpty(),
                    name = validation.normalizedTitle,
                    brand = candidate.brand ?: existing?.brand,
                    grams = candidate.grams ?: existing?.grams,
                    karat = validation.normalizedKarat,
                    purity = validation.normalizedPurity,
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

                if (distinctPids != null) {
                    if (!distinctPids.contains(targetRetailerId)) {
                        distinctPids.add(targetRetailerId)
                        validSaved++
                    }
                } else {
                    validSaved++
                }
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
    ): PdpRefreshResult {
        val config = ScraperConfigProvider.get()
        val unrefreshed = database.dao().allProducts().filter { p ->
            p.store == store &&
                p.checkedAt < startedAt &&
                p.status != "unavailable" &&
                (p.karat == 24.0 || p.karat == null)
        }.take(config.limits.maxPdpItemsPerStore)

        if (unrefreshed.isEmpty()) return PdpRefreshResult(0, 0, 0, 0)

        activityRepository?.log(
            RefreshLogSeverity.Info,
            store,
            "[$store] Starting sequential PDP verification for ${unrefreshed.size} stale/unrefreshed items..."
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

        var pdpRequests = 0
        var pdpUpdated = 0
        var pdpUnavailable = 0
        var pdpFailed = 0
        var abortPdp = false

        for ((idx, product) in unrefreshed.withIndex()) {
            if (abortPdp) break
            val delayMs = if (store == "amazon.in") config.delays.pdpAmazonDelayMs else config.delays.pdpInterRequestDelayMs
            delay(delayMs)

            val endpoint = when (store) {
                "ajio.com" -> {
                    val cleanId = product.retailerId.substringBefore('_')
                    "https://www.ajio.com/api/p/$cleanId"
                }
                "myntra.com" -> {
                    val numericId = product.retailerId.filter { it.isDigit() }
                    if (numericId.isNotBlank()) "https://www.myntra.com/gateway/v2/product/$numericId"
                    else product.canonicalUrl.takeIf { it.isNotBlank() }
                }
                "amazon.in", "flipkart.com", "shopsy.in" -> product.canonicalUrl.takeIf { it.isNotBlank() }
                else -> null
            } ?: continue

            pdpRequests++
            try {
                val response = when (store) {
                    "ajio.com" -> CronetNetworkClient.executeCronetApiRequest(endpoint, pincode ?: "560048")
                    "myntra.com" -> if (endpoint.contains("gateway")) {
                        CronetNetworkClient.executeCronetWithHeaders(endpoint, gatewayHeaders)
                    } else {
                        CronetNetworkClient.executeCronetWithHeaders(endpoint, desktopHeaders)
                    }
                    else -> CronetNetworkClient.executeCronetWithHeaders(endpoint, desktopHeaders)
                }

                if (response.status == 403 || response.status == 429) {
                    activityRepository?.log(
                        RefreshLogSeverity.Warning,
                        store,
                        "[$store] PDP verification hit HTTP ${response.status} on item ${idx + 1}/${unrefreshed.size} (${product.retailerId}). Triggering network session reset and halting PDP gracefully."
                    )
                    CronetNetworkClient.resetSession()
                    abortPdp = true
                    pdpFailed++
                    break
                }

                val now = System.currentTimeMillis()
                when (val lookup = ProductLookup.parse(store, response.status, response.body, endpoint)) {
                    is ProductLookup.Available -> {
                        val validation = Product24KValidator.validate(
                            name = lookup.name ?: product.name,
                            store = store,
                            karat = product.karat,
                            purity = product.purity,
                            price = lookup.price,
                            grams = lookup.grams ?: product.grams,
                            brand = lookup.brand ?: product.brand,
                            canonicalUrl = product.canonicalUrl,
                            retailerId = product.retailerId,
                        )
                        if (!validation.isValid) {
                            database.dao().deleteProduct(product.id)
                            continue
                        }
                        val updatedProduct = product.copy(
                            name = validation.normalizedTitle,
                            karat = validation.normalizedKarat,
                            purity = validation.normalizedPurity,
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
                                if (!database.dao().hasPriceHistory(product.id, lookup.price, lookup.couponPrice, now)) {
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
                        }
                        pdpUpdated++
                    }
                    is ProductLookup.Unavailable -> {
                        val validation = Product24KValidator.validate(
                            name = product.name,
                            store = store,
                            karat = product.karat,
                            purity = product.purity,
                            price = lookup.price ?: product.price,
                            grams = product.grams,
                            brand = product.brand,
                            canonicalUrl = product.canonicalUrl,
                            retailerId = product.retailerId,
                        )
                        if (!validation.isValid) {
                            database.dao().deleteProduct(product.id)
                            continue
                        }
                        database.dao().upsertProduct(
                            product.copy(
                                name = validation.normalizedTitle,
                                karat = validation.normalizedKarat,
                                purity = validation.normalizedPurity,
                                status = "unavailable",
                                deliverable = false,
                                checkedAt = now,
                                price = lookup.price ?: product.price,
                            )
                        )
                        pdpUnavailable++
                    }
                    ProductLookup.Unknown -> {
                        pdpFailed++
                    }
                }
            } catch (e: Exception) {
                pdpFailed++
                Log.w(tag, "PDP verification error for ${product.retailerId}: ${e.message}")
            }
        }

        if (pdpUpdated > 0 || pdpUnavailable > 0) {
            activityRepository?.log(
                RefreshLogSeverity.Info,
                store,
                "[$store] PDP verification complete: $pdpUpdated refreshed live, $pdpUnavailable marked unavailable, $pdpFailed failed/skipped"
            )
        }

        return PdpRefreshResult(
            requests = pdpRequests,
            successful = pdpUpdated + pdpUnavailable,
            failed = pdpFailed,
            required = unrefreshed.size,
        )
    }

    private suspend fun recordRawPayload(id: String, store: String, json: String) {
        val payload = RawBridgePayloadEntity(
            id = id,
            store = store,
            receivedAt = System.currentTimeMillis(),
            json = json,
        )
        if (internalDatabase != null) {
            internalDatabase.dao().insertRawPayload(payload)
        } else {
            database.dao().insertRawPayload(payload)
        }
        internalDatabase?.dao()?.insertRawPayload(payload)
    }
}
