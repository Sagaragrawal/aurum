package com.aurum.intelligence.data.engine.stores

import android.content.Context
import android.util.Log
import com.aurum.intelligence.data.db.AurumDatabase
import com.aurum.intelligence.data.db.AurumInternalDatabase
import com.aurum.intelligence.data.db.DatabaseBackupManager
import com.aurum.intelligence.data.db.ScraperExecutionMetricsEntity
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.repository.RefreshActivityRepository
import com.aurum.intelligence.data.repository.RefreshLogSeverity
import com.aurum.intelligence.parsers.FlipkartNativeParser
import java.util.UUID
import kotlinx.coroutines.delay

class FlipkartScraperEngine(
    private val database: AurumDatabase,
    private val internalDatabase: AurumInternalDatabase? = null,
    private val activityRepository: RefreshActivityRepository? = null,
    private val context: Context? = null,
) : StoreScraperEngine {

    override val storeKey: String = "flipkart.com"
    private val tag = "FlipkartScraperEngine"

    override suspend fun refreshStore(
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
            runCatching { database.dao().markAllStoreProductsStale(storeKey) }
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
                        storeKey,
                        "[Flipkart] Fetching ${target.name} (page 1)..."
                    )
                    plpRequests++
                    val resp1 = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, targetUrl, flipkartHeaders, activityRepository = activityRepository)
                    if (resp1.status in 200..299 && resp1.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_1", resp1.body, "html")
                        StoreEngineHelpers.recordRawPayload(
                            database = database,
                            internalDatabase = internalDatabase,
                            id = UUID.randomUUID().toString(),
                            store = "flipkart_master_${target.name.replace(" ", "_")}_page_1",
                            json = resp1.body,
                        )
                        val parsed1 = FlipkartNativeParser.parse(resp1.body, storeKey, bullionRate24)
                        var targetDiscovered = parsed1.candidates.size
                        var targetValid = StoreEngineHelpers.saveCandidates(database, storeKey, parsed1.candidates, pincode, distinctPids)
                        discovered += targetDiscovered
                        valid += targetValid

                        val pageCap = if (target.isMinutes) 1 else config.limits.maxPagesPerStore
                        if (pageCap > 1) {
                            var consecutiveEmptyPages = 0
                            for (page in 2..pageCap) {
                                delay(config.delays.flipkartPageDelayMs)
                                val pageParam = if (targetUrl.contains("?")) "&page=$page" else "?page=$page"
                                val pageUrl = "${targetUrl}$pageParam"
                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    storeKey,
                                    "[Flipkart] Fetching ${target.name} (page $page of $pageCap)..."
                                )
                                val resp = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, pageUrl, flipkartHeaders, activityRepository = activityRepository)
                                if (resp.status in 200..299 && resp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_$page", resp.body, "html")
                                    StoreEngineHelpers.recordRawPayload(
                                        database = database,
                                        internalDatabase = internalDatabase,
                                        id = UUID.randomUUID().toString(),
                                        store = "flipkart_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = resp.body,
                                    )
                                    val p = FlipkartNativeParser.parse(resp.body, storeKey, bullionRate24)
                                    if (p.candidates.isEmpty()) {
                                        consecutiveEmptyPages++
                                        if (consecutiveEmptyPages >= 3) break
                                    } else {
                                        consecutiveEmptyPages = 0
                                    }
                                    val newCandidates = p.candidates.filter { it.retailerId !in distinctPids }
                                    val s = StoreEngineHelpers.saveCandidates(database, storeKey, newCandidates, pincode, distinctPids)
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
                            storeKey,
                            "[Flipkart] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                        )
                    } else {
                        lastError = "HTTP ${resp1.status}"
                        activityRepository?.log(
                            RefreshLogSeverity.Warning,
                            storeKey,
                            "[Flipkart] ${target.name} returned HTTP ${resp1.status}"
                        )
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(tag, "Flipkart refresh error for ${target.name}: ${e.message}", e)
                    activityRepository?.log(
                        RefreshLogSeverity.Error,
                        storeKey,
                        "[Flipkart] ${target.name} failed: ${e.message}"
                    )
                }
            }

            val pdpResult = StoreEngineHelpers.refreshStorePdp(database, internalDatabase, activityRepository, context, storeKey, start, pincode)
            valid += pdpResult.successful

            val duration = System.currentTimeMillis() - start
            val result = StoreRefreshProgress(
                store = storeKey,
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
                        store = storeKey,
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
                    storeKey,
                    "Coverage: $discovered discovered, $valid valid gold items ingested (${duration}ms)",
                )
            } else {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    storeKey,
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
            activityRepository?.log(RefreshLogSeverity.Error, storeKey, "[Flipkart] Engine error: $errStr")
            val progress = StoreRefreshProgress(
                store = storeKey,
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
}
