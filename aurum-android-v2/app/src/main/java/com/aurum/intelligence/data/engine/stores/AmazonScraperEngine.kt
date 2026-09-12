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
import com.aurum.intelligence.parsers.AmazonNativeParser
import java.util.UUID
import kotlinx.coroutines.delay

class AmazonScraperEngine(
    private val database: AurumDatabase,
    private val internalDatabase: AurumInternalDatabase? = null,
    private val activityRepository: RefreshActivityRepository? = null,
    private val context: Context? = null,
) : StoreScraperEngine {

    override val storeKey: String = "amazon.in"
    private val tag = "AmazonScraperEngine"

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
            val amazonTargets = config.amazonTargets
            val pincodeHeaders = LocationHelper.buildPincodeHeaders(pincode)
            val amazonHeaders = (config.stores["amazon"]?.headers?.takeIf { it.isNotEmpty() } ?: config.network.desktopHeaders) + pincodeHeaders

            for (target in amazonTargets) {
                val urlStart = System.currentTimeMillis()
                try {
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        storeKey,
                        "[Amazon] Fetching ${target.name} (page 1)..."
                    )
                    val page1Url = if (target.url.contains("ref=")) target.url else "${target.url}&ref=sr_pg_1"
                    plpRequests++
                    val resp1 = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, page1Url, amazonHeaders, activityRepository = activityRepository)
                    if (resp1.status in 200..299 && resp1.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_1", resp1.body, "html")
                        StoreEngineHelpers.recordRawPayload(
                            database = database,
                            internalDatabase = internalDatabase,
                            id = UUID.randomUUID().toString(),
                            store = "amazon_master_${target.name.replace(" ", "_")}_page_1",
                            json = resp1.body,
                        )
                        val parsed1 = AmazonNativeParser.parse(resp1.body, bullionRate24)
                        val newCandidates1 = parsed1.candidates.filter { it.retailerId !in distinctPids }
                        var targetDiscovered = newCandidates1.size
                        var targetValid = StoreEngineHelpers.saveCandidates(database, storeKey, newCandidates1, pincode, distinctPids)
                        discovered += targetDiscovered
                        valid += targetValid

                        val pageSize = config.limits.maxPdpItemsPerStore.coerceAtLeast(16)
                        val pageCap = if (totalAvailable > 0) (totalAvailable + pageSize - 1) / pageSize else config.limits.maxPagesPerStore
                        if (pageCap > 1) {
                            var consecutiveEmptyPages = 0
                            for (page in 2..pageCap) {
                                delay(config.delays.amazonPageDelayMs)
                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    storeKey,
                                    "[Amazon] Fetching ${target.name} (page $page of $pageCap)..."
                                )
                                val cleanBaseUrl = target.url.replace(Regex("&ref=[^&]*"), "")
                                val pageUrl = if (cleanBaseUrl.contains("?")) "$cleanBaseUrl&page=$page" else "$cleanBaseUrl?page=$page"
                                val resp = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, pageUrl, amazonHeaders, activityRepository = activityRepository)
                                if (resp.status in 200..299 && resp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_$page", resp.body, "html")
                                    StoreEngineHelpers.recordRawPayload(
                                        database = database,
                                        internalDatabase = internalDatabase,
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
                            "[Amazon] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                        )
                    } else {
                        lastError = "HTTP ${resp1.status}"
                        activityRepository?.log(
                            RefreshLogSeverity.Warning,
                            storeKey,
                            "[Amazon] ${target.name} returned HTTP ${resp1.status}"
                        )
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(tag, "Amazon refresh error for ${target.name}: ${e.message}", e)
                    activityRepository?.log(
                        RefreshLogSeverity.Error,
                        storeKey,
                        "[Amazon] ${target.name} failed: ${e.message}"
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
            val errStr = e.message ?: "Amazon error"
            Log.e(tag, "Amazon engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, storeKey, "[Amazon] Engine error: $errStr")
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
