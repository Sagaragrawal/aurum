package com.aurum.intelligence.data.engine
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.util.Log
import androidx.room.withTransaction
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
    private val context: android.content.Context? = null,
) {

    private val tag = "ParallelRefreshEngine"

    suspend fun refreshAllParallel(
        pincode: String = ScraperConfigProvider.get().location.defaultPincode,
        latitude: Double? = null,
        longitude: Double? = null,
        maxPagesPerStore: Int = ScraperConfigProvider.get().limits.defaultPagesPerRefresh,
        targetStores: Set<String>? = null,
        onProgress: (StoreRefreshProgress) -> Unit = {},
    ): FullRefreshSummary = withContext(Dispatchers.IO) {
        val overallStart = System.currentTimeMillis()
        val activeStores = targetStores?.takeIf { it.isNotEmpty() }
        val targetLabel = activeStores?.joinToString(", ") ?: "all stores"

        // 0. Automatic Log and Raw Page Cleanup
        runCatching {
            if (activeStores == null) {
                database.dao().clearRefreshActivity()
                internalDatabase?.dao()?.clearRefreshActivity()
                DatabaseBackupManager.clearAllRawPages()
            } else {
                for (st in activeStores) {
                    database.dao().clearStoreRefreshActivity(st)
                    internalDatabase?.dao()?.clearStoreRefreshActivity(st)
                    DatabaseBackupManager.clearStoreRawPages(st)
                }
            }
        }

        activityRepository?.log(RefreshLogSeverity.Info, null, "Starting native parallel refresh for $targetLabel and bullion")

        // 1. Get latest benchmark bullion rate for price plausibility evaluation
        val initialBullion = database.dao().latestBullionHistory()
        val initialBenchmarkRate = initialBullion?.price24

        // 2. Dispatch selected stores and bullion in parallel
        val (storeResults, bullionRates) = coroutineScope {
            val ajioDeferred = if (activeStores == null || "ajio.com" in activeStores) {
                async { refreshAjio(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            } else null

            val flipkartDeferred = if (activeStores == null || "flipkart.com" in activeStores) {
                async { refreshFlipkart(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            } else null

            val shopsyDeferred = if (activeStores == null || "shopsy.in" in activeStores) {
                async { refreshShopsy(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            } else null

            val amazonDeferred = if (activeStores == null || "amazon.in" in activeStores) {
                async { refreshAmazon(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            } else null

            val myntraDeferred = if (activeStores == null || "myntra.com" in activeStores) {
                async { refreshMyntra(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
            } else null

            val bullionDeferred = async { refreshBullion() }

            val stores = listOfNotNull(
                ajioDeferred?.await(),
                flipkartDeferred?.await(),
                shopsyDeferred?.await(),
                amazonDeferred?.await(),
                myntraDeferred?.await(),
            )
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

        // Ensure no stale products remain across any stores after full parallel refresh
        runCatching { database.dao().markAllStaleProductsUnavailable() }

        // Sync databases to /storage/emulated/0/aurum immediately upon refresh completion
        context?.let { ctx ->
            DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
        }

        FullRefreshSummary(
            storeResults = storeResults,
            bullionResults = bullionRates,
            totalDiscovered = totalDiscovered,
            totalValid = totalValid,
            totalDurationMs = totalDuration,
        )
    }

    // =========================================================================
    private suspend fun fetchStorePageWithRecovery(
        store: String,
        url: String,
        headers: Map<String, String>,
        maxRetries: Int = 3,
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
                    response.body.contains("request blocked", ignoreCase = true) ||
                    response.body.contains("access denied", ignoreCase = true) ||
                    response.body.contains("captcha", ignoreCase = true) ||
                    response.body.contains("perimeterx", ignoreCase = true)
                ))

            if (response.status in 200..299 && response.body.isNotBlank() && !isBlocked) {
                return response
            }

            if (attempt < maxRetries) {
                activityRepository?.log(
                    RefreshLogSeverity.Warning,
                    store,
                    "[$store] Request returned HTTP ${response.status} (attempt $attempt/$maxRetries). Dropping cookies & resetting session...",
                )

                // Drop session & cookies
                CronetNetworkClient.resetSession()

                // Re-build fresh headers
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
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale("ajio.com") }
            val distinctPids = mutableSetOf<String>()
            val config = ScraperConfigProvider.get()
            val ajioTargets = config.ajioTargets
            val safetyCeiling = maxPages.coerceAtLeast(config.limits.maxPagesPerStore)
            val ajioApiHeaders = config.network.cronetApiRequestHeaders + LocationHelper.buildPincodeHeaders(pincode)

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
                    val resp0 = fetchStorePageWithRecovery("ajio.com", "${targetBaseUrl}&currentPage=0", ajioApiHeaders)

                    if (resp0.status in 200..299 && resp0.body.isNotBlank()) {
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

                        DatabaseBackupManager.saveRawPage("ajio.com", "${target.name}_page_0", resp0.body, "json")
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
                                val pResp = fetchStorePageWithRecovery("ajio.com", "${targetBaseUrl}&currentPage=$page", ajioApiHeaders)

                                if (pResp.status in 200..299 && pResp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage("ajio.com", "${target.name}_page_$page", pResp.body, "json")
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
            runCatching {
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
            }

            if (isBlocked) {
                activityRepository?.log(
                    RefreshLogSeverity.Warning,
                    "ajio.com",
                    "[AJIO] BLOCKED / PARTIAL_SUCCESS: Existing catalogue preserved (${duration}ms)",
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
            context?.let { ctx ->
                DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
            }
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val errStr = e.message ?: "Ajio error"
            Log.e(tag, "Ajio engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, "ajio.com", "[AJIO] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = "ajio.com",
                itemsDiscovered = discovered,
                itemsValid = valid,
                durationMs = duration,
                isComplete = true,
                error = errStr,
            )
            onProgress(progress)
            progress
        }
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
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale("flipkart.com") }
            val distinctPids = mutableSetOf<String>()
            val config = ScraperConfigProvider.get()
            val flipkartTargets = config.flipkartTargets
            val pincodeHeaders = LocationHelper.buildPincodeHeaders(pincode)
            val flipkartHeaders = (config.stores["flipkart"]?.headers?.takeIf { it.isNotEmpty() } ?: config.network.desktopHeaders) + pincodeHeaders

            for (target in flipkartTargets) {
                val urlStart = System.currentTimeMillis()
                val targetUrl = target.url
                try {
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "flipkart.com",
                        "[Flipkart] Fetching ${target.name} (page 1)..."
                    )
                    plpRequests++
                    val resp1 = fetchStorePageWithRecovery("flipkart.com", targetUrl, flipkartHeaders)
                    if (resp1.status in 200..299 && resp1.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage("flipkart.com", "${target.name}_page_1", resp1.body, "html")
                        recordRawPayload(
                            id = UUID.randomUUID().toString(),
                            store = "flipkart_master_${target.name.replace(" ", "_")}_page_1",
                            json = resp1.body,
                        )
                        val parsed1 = FlipkartNativeParser.parse(resp1.body, "flipkart.com", bullionRate24)
                        var targetDiscovered = parsed1.candidates.size
                        var targetValid = saveCandidates("flipkart.com", parsed1.candidates, pincode, distinctPids)
                        discovered += targetDiscovered
                        valid += targetValid

                        val pageCap = if (target.isMinutes) 1 else minOf(30, config.limits.maxPagesPerStore)
                        if (pageCap > 1) {
                            var consecutiveEmptyPages = 0
                            for (page in 2..pageCap) {
                                delay(config.delays.flipkartPageDelayMs)
                                val pageParam = if (targetUrl.contains("?")) "&page=$page" else "?page=$page"
                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "flipkart.com",
                                    "[Flipkart] Fetching ${target.name} (page $page of $pageCap)..."
                                )
                                val resp = fetchStorePageWithRecovery("flipkart.com", "${targetUrl}$pageParam", flipkartHeaders)
                                if (resp.status in 200..299 && resp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage("flipkart.com", "${target.name}_page_$page", resp.body, "html")
                                    recordRawPayload(
                                        id = UUID.randomUUID().toString(),
                                        store = "flipkart_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = resp.body,
                                    )
                                    val p = FlipkartNativeParser.parse(resp.body, "flipkart.com", bullionRate24)
                                    if (p.candidates.isEmpty()) {
                                        consecutiveEmptyPages++
                                        if (consecutiveEmptyPages >= 3) break
                                    } else {
                                        consecutiveEmptyPages = 0
                                    }
                                    val newCandidates = p.candidates.filter { it.retailerId !in distinctPids }
                                    val s = saveCandidates("flipkart.com", newCandidates, pincode, distinctPids)
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
            runCatching {
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
            }
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
            context?.let { ctx ->
                DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
            }
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val errStr = e.message ?: "Flipkart error"
            Log.e(tag, "Flipkart engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, "flipkart.com", "[Flipkart] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = "flipkart.com",
                itemsDiscovered = discovered,
                itemsValid = valid,
                durationMs = duration,
                isComplete = true,
                error = errStr,
            )
            onProgress(progress)
            progress
        }
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
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale("shopsy.in") }
            val distinctPids = mutableSetOf<String>()
            val config = ScraperConfigProvider.get()
            val shopsyTargets = config.shopsyTargets
            val pincodeHeaders = LocationHelper.buildPincodeHeaders(pincode)
            val shopsyHeaders = (config.stores["shopsy"]?.headers?.takeIf { it.isNotEmpty() } ?: config.network.desktopHeaders) + pincodeHeaders

            for (target in shopsyTargets) {
                val urlStart = System.currentTimeMillis()
                val targetUrl = target.url
                try {
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "shopsy.in",
                        "[Shopsy] Fetching ${target.name} (page 1)..."
                    )
                    plpRequests++
                    val resp1 = fetchStorePageWithRecovery("shopsy.in", targetUrl, shopsyHeaders)
                    if (resp1.status in 200..299 && resp1.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage("shopsy.in", "${target.name}_page_1", resp1.body, "html")
                        recordRawPayload(
                            id = UUID.randomUUID().toString(),
                            store = "shopsy_master_${target.name.replace(" ", "_")}_page_1",
                            json = resp1.body,
                        )
                        val parsed1 = FlipkartNativeParser.parse(resp1.body, "shopsy.in", bullionRate24)
                        var targetDiscovered = parsed1.candidates.size
                        var targetValid = saveCandidates("shopsy.in", parsed1.candidates, pincode, distinctPids)
                        discovered += targetDiscovered
                        valid += targetValid

                        val pageCap = minOf(30, config.limits.maxPagesPerStore)
                        if (pageCap > 1) {
                            var consecutiveEmptyPages = 0
                            for (page in 2..pageCap) {
                                delay(config.delays.shopsyPageDelayMs)
                                val pageParam = if (targetUrl.contains("?")) "&page=$page" else "?page=$page"
                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "shopsy.in",
                                    "[Shopsy] Fetching ${target.name} (page $page of $pageCap)..."
                                )
                                val resp = fetchStorePageWithRecovery("shopsy.in", "${targetUrl}$pageParam", shopsyHeaders)
                                if (resp.status in 200..299 && resp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage("shopsy.in", "${target.name}_page_$page", resp.body, "html")
                                    recordRawPayload(
                                        id = UUID.randomUUID().toString(),
                                        store = "shopsy_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = resp.body,
                                    )
                                    val p = FlipkartNativeParser.parse(resp.body, "shopsy.in", bullionRate24)
                                    if (p.candidates.isEmpty()) {
                                        consecutiveEmptyPages++
                                        if (consecutiveEmptyPages >= 3) break
                                    } else {
                                        consecutiveEmptyPages = 0
                                    }
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
            runCatching {
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
            }
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
            context?.let { ctx ->
                DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
            }
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val errStr = e.message ?: "Shopsy error"
            Log.e(tag, "Shopsy engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, "shopsy.in", "[Shopsy] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = "shopsy.in",
                itemsDiscovered = discovered,
                itemsValid = valid,
                durationMs = duration,
                isComplete = true,
                error = errStr,
            )
            onProgress(progress)
            progress
        }
    }

    // =========================================================================
    // AMAZON ENGINE
    // =========================================================================
    private suspend fun refreshAmazon(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress {
        val start = System.currentTimeMillis()
        var discovered = 0
        var valid = 0
        var lastError: String? = null
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale("amazon.in") }
            val distinctPids = mutableSetOf<String>()
            val config = ScraperConfigProvider.get()
            val amazonTargets = config.amazonTargets
            val pincodeHeaders = LocationHelper.buildPincodeHeaders(pincode)
            val amazonHeaders = (config.stores["amazon"]?.headers?.takeIf { it.isNotEmpty() } ?: config.network.desktopHeaders) + pincodeHeaders

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
                    val resp1 = fetchStorePageWithRecovery("amazon.in", page1Url, amazonHeaders)
                    if (resp1.status in 200..299 && resp1.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage("amazon.in", "${target.name}_page_1", resp1.body, "html")
                        recordRawPayload(
                            id = UUID.randomUUID().toString(),
                            store = "amazon_master_${target.name.replace(" ", "_")}_page_1",
                            json = resp1.body,
                        )
                        val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                        val newCandidates1 = parsed1.candidates.filter { it.retailerId !in distinctPids }
                        var targetDiscovered = newCandidates1.size
                        var targetValid = saveCandidates("amazon.in", newCandidates1, null, distinctPids)
                        discovered += targetDiscovered
                        valid += targetValid

                        val totalAvailable = parsed1.totalResults
                        val pageCap = if (totalAvailable > 0) minOf(20, (totalAvailable + 15) / 16) else minOf(20, config.limits.maxPagesPerStore)
                        if (pageCap > 1) {
                            var consecutiveEmptyPages = 0
                            for (page in 2..pageCap) {
                                delay(config.delays.amazonPageDelayMs) // Gentle pacing avoids Amazon bot detection
                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "amazon.in",
                                    "[Amazon] Fetching ${target.name} (page $page of $pageCap)..."
                                )
                                val cleanBaseUrl = target.url.replace(Regex("&ref=[^&]*"), "")
                                val pageUrl = if (cleanBaseUrl.contains("?")) "$cleanBaseUrl&page=$page" else "$cleanBaseUrl?page=$page"
                                val resp = fetchStorePageWithRecovery("amazon.in", pageUrl, amazonHeaders)
                                if (resp.status in 200..299 && resp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage("amazon.in", "${target.name}_page_$page", resp.body, "html")
                                    recordRawPayload(
                                        id = UUID.randomUUID().toString(),
                                        store = "amazon_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = resp.body,
                                    )
                                    val p = AmazonNativeParser.parse(resp.body, bullionRate24)
                                    if (p.candidates.isEmpty()) {
                                        consecutiveEmptyPages++
                                        if (consecutiveEmptyPages >= 3) break
                                    } else {
                                        consecutiveEmptyPages = 0
                                    }
                                    val newCandidates = p.candidates.filter { it.retailerId !in distinctPids }
                                    val s = saveCandidates("amazon.in", newCandidates, null, distinctPids)
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
            runCatching {
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
            }
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
            context?.let { ctx ->
                DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
            }
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val errStr = e.message ?: "Amazon error"
            Log.e(tag, "Amazon engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, "amazon.in", "[Amazon] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = "amazon.in",
                itemsDiscovered = discovered,
                itemsValid = valid,
                durationMs = duration,
                isComplete = true,
                error = errStr,
            )
            onProgress(progress)
            progress
        }
    }

    // =========================================================================
    // MYNTRA ENGINE (Gateway v4 API + Web Fallback)
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
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale("myntra.com") }
            val distinctPids = mutableSetOf<String>()
            val config = ScraperConfigProvider.get()
            val gatewayHeaders = config.stores["myntra"]?.gatewayHeaders ?: emptyMap()
            val webHeaders = config.stores["myntra"]?.webHeaders ?: config.network.desktopHeaders
            val myntraTargets = config.myntraTargets

            for (target in myntraTargets) {
                val urlStart = System.currentTimeMillis()
                try {
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "myntra.com",
                        "[Myntra] Establishing session for ${target.name}..."
                    )
                    // 1. Visit web URL first to populate Akamai / Myntra session cookies in Cronet
                    plpRequests++
                    val initResp = fetchStorePageWithRecovery("myntra.com", target.url, webHeaders)

                    val rawCookies = (initResp.headers["set-cookie"] ?: initResp.headers["Set-Cookie"] ?: emptyList())
                        .map { it.substringBefore(';') }
                        .filter { it.isNotBlank() }
                        .joinToString("; ")

                    val sessionGatewayHeaders = if (rawCookies.isNotBlank()) {
                        gatewayHeaders + ("Cookie" to rawCookies)
                    } else gatewayHeaders

                    var targetDiscovered = 0
                    var targetValid = 0

                    val categorySlug = if (target.slug.isNotBlank()) target.slug else "gold-coin"
                    val gatewayBase = config.stores["myntra"]?.gatewayBaseUrl?.takeIf { it.isNotBlank() } ?: "https://www.myntra.com/gateway/v4/search/"
                    val page1ApiUrl = "${gatewayBase}$categorySlug?rows=100&o=0&plaEnabled=true&xdEnabled=false&isFacet=true&p=1&pincode=$pincode"
                    plpRequests++
                    var pageResp = fetchStorePageWithRecovery("myntra.com", page1ApiUrl, sessionGatewayHeaders)
                    if (pageResp.status != 200) {
                        pageResp = initResp
                    }

                    if (pageResp.status in 200..299 && pageResp.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage("myntra.com", "${target.name}_page_1", pageResp.body, if (pageResp.body.trimStart().startsWith("<")) "html" else "json")
                        recordRawPayload(
                            id = UUID.randomUUID().toString(),
                            store = "myntra_master_${target.name.replace(" ", "_")}_page_1",
                            json = pageResp.body,
                        )
                        var currentPaginationCtx = pageResp.headers["pagination-context"]?.firstOrNull()
                            ?: pageResp.headers["Pagination-Context"]?.firstOrNull()

                        val parsed = MyntraNativeParser.parse(pageResp.body, bullionRate24)
                        val s0 = saveCandidates("myntra.com", parsed.candidates, pincode, distinctPids)
                        targetDiscovered += parsed.candidates.size
                        targetValid += s0
                        discovered += parsed.candidates.size
                        valid += s0

                        val seenIds = parsed.candidates.map { it.retailerId }.toMutableSet()
                        val totalAvailable = parsed.totalCount
                        val totalPagesAvailable = if (totalAvailable > 0) (totalAvailable + 99) / 100 else config.limits.maxPagesPerStore
                        val pageLimit = totalPagesAvailable

                        if (pageLimit > 1) {
                            for (page in 2..pageLimit) {
                                delay(config.delays.myntraApiPageDelayMs)
                                val offset = (page - 1) * 100
                                val nextApiUrl = "${gatewayBase}$categorySlug?rows=100&o=$offset&plaEnabled=true&xdEnabled=false&isFacet=true&p=$page&pincode=$pincode"
                                val nextHeaders = if (!currentPaginationCtx.isNullOrBlank()) {
                                    sessionGatewayHeaders + ("pagination-context" to currentPaginationCtx)
                                } else sessionGatewayHeaders

                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    "myntra.com",
                                    "[Myntra] Fetching ${target.name} (page $page of $pageLimit)..."
                                )
                                var r = fetchStorePageWithRecovery("myntra.com", nextApiUrl, nextHeaders)
                                if (r.status != 200) {
                                    val pageSep = if (target.url.contains("?")) "&" else "?"
                                    val nextWebUrl = "${target.url}${pageSep}p=$page"
                                    r = fetchStorePageWithRecovery("myntra.com", nextWebUrl, webHeaders)
                                }

                                val newCtx = r.headers["pagination-context"]?.firstOrNull()
                                    ?: r.headers["Pagination-Context"]?.firstOrNull()
                                if (!newCtx.isNullOrBlank()) currentPaginationCtx = newCtx

                                if (r.status in 200..299) {
                                    DatabaseBackupManager.saveRawPage("myntra.com", "${target.name}_page_$page", r.body, if (r.body.trimStart().startsWith("<")) "html" else "json")
                                    recordRawPayload(
                                        id = UUID.randomUUID().toString(),
                                        store = "myntra_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = r.body,
                                    )
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
                        activityRepository?.log(
                            RefreshLogSeverity.Warning,
                            "myntra.com",
                            "[Myntra] ${target.name} page 1 returned HTTP ${pageResp.status}"
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
            runCatching {
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
            }
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
            context?.let { ctx ->
                DatabaseBackupManager.syncDatabasesToExternal(ctx, database, internalDatabase)
            }
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val errStr = e.message ?: "Myntra error"
            Log.e(tag, "Myntra engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, "myntra.com", "[Myntra] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = "myntra.com",
                itemsDiscovered = discovered,
                itemsValid = valid,
                durationMs = duration,
                isComplete = true,
                error = errStr,
            )
            onProgress(progress)
            progress
        }
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

                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        "bullion",
                        "[$label] Rendered bullion rate saved: 24K ₹${parsed.price24}/g, 22K ₹${parsed.price22}/g",
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

        database.withTransaction {
            val storeProducts = database.dao().productsByStore(store)
            val byRetailerId = HashMap<String, ProductEntity>(storeProducts.size * 2)
            val byCanonicalUrl = HashMap<String, ProductEntity>(storeProducts.size * 2)
            for (p in storeProducts) {
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
                    val existing = byRetailerId[candidate.retailerId]
                        ?: byRetailerId[cleanRetailerId]
                        ?: (if (candidate.canonicalUrl.isNotBlank()) byCanonicalUrl[candidate.canonicalUrl] else null)

                    val entityId = existing?.id ?: UUID.randomUUID().toString()
                    val targetStore = existing?.store ?: store
                    val targetRetailerId = existing?.retailerId ?: candidate.retailerId

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
                    Log.w(tag, "Failed to prepare product ${candidate.retailerId}: ${e.message}")
                }
            }

            if (entitiesToUpsert.isNotEmpty()) {
                database.dao().upsertProducts(entitiesToUpsert)
            }
            if (historiesToInsert.isNotEmpty()) {
                database.dao().insertPriceHistories(historiesToInsert)
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
        val desktopHeaders = config.network.desktopHeaders + pincodeHeaders
        val ajioPdpHeaders = config.network.ajioPdpHeaders + pincodeHeaders
        val gatewayHeaders = config.stores["myntra"]?.gatewayHeaders ?: emptyMap()

        var pdpRequests = 0
        var pdpUpdated = 0
        var pdpUnavailable = 0
        var pdpFailed = 0
        var consecutiveFailures = 0
        var abortPdp = false

        val concurrency = config.limits.pdpConcurrency.coerceIn(1, 20)
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)

        coroutineScope {
            unrefreshed.chunked(concurrency * 2).forEach { chunk ->
                if (abortPdp) return@forEach
                chunk.map { product ->
                    async {
                        if (abortPdp) return@async
                        semaphore.withPermit {
                            val delayMs = if (store == "amazon.in") config.delays.pdpAmazonDelayMs else config.delays.pdpInterRequestDelayMs
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
                                val response = when (store) {
                                    "ajio.com" -> fetchStorePageWithRecovery("ajio.com", endpoint, ajioPdpHeaders)
                                    else -> fetchStorePageWithRecovery(store, endpoint, desktopHeaders)
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
                                Log.w(tag, "PDP verification error for ${product.retailerId}: ${e.message}")
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

    private suspend fun recordRawPayload(id: String, store: String, json: String) {
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
            internalDatabase.dao().trimRawPayloads(5)
        } else {
            database.dao().insertRawPayload(payload)
            database.dao().trimRawPayloads(5)
        }
    }
}
