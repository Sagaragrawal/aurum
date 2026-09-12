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
import com.aurum.intelligence.parsers.AjioNativeParser
import java.util.UUID
import kotlinx.coroutines.delay

class AjioScraperEngine(
    private val database: AurumDatabase,
    private val internalDatabase: AurumInternalDatabase? = null,
    private val activityRepository: RefreshActivityRepository? = null,
    private val context: Context? = null,
) : StoreScraperEngine {

    override val storeKey: String = "ajio.com"
    private val tag = "AjioScraperEngine"

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
        var isBlocked = false
        var plpRequests = 0

        return try {
            runCatching { database.dao().markAllStoreProductsStale(storeKey) }
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
                        storeKey,
                        "[AJIO] DISCOVERING page=0 target=${target.name}..."
                    )
                    plpRequests++
                    val resp0 = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, "${targetBaseUrl}&currentPage=0", ajioApiHeaders, activityRepository = activityRepository)

                    if (resp0.status in 200..299 && resp0.body.isNotBlank()) {
                        val parsed0 = AjioNativeParser.parse(resp0.body, bullionRate24)
                        val p0Discovered = parsed0.candidates.size
                        val p0Saved = StoreEngineHelpers.saveCandidates(database, storeKey, parsed0.candidates, pincode, distinctPids)
                        targetDiscovered += p0Discovered
                        targetValid += p0Saved
                        discovered += p0Discovered
                        valid += p0Saved

                        activityRepository?.log(
                            RefreshLogSeverity.Info,
                            storeKey,
                            "[AJIO] DISCOVERING page=0 HTTP status=200 discovered=$p0Discovered accepted=$p0Saved totalAvailable=${parsed0.totalResults}"
                        )

                        DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_0", resp0.body, "json")
                        StoreEngineHelpers.recordRawPayload(
                            database = database,
                            internalDatabase = internalDatabase,
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
                                    storeKey,
                                    "[AJIO] DISCOVERING page=$page target=${target.name}..."
                                )
                                plpRequests++
                                val pResp = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, "${targetBaseUrl}&currentPage=$page", ajioApiHeaders, activityRepository = activityRepository)

                                if (pResp.status in 200..299 && pResp.body.isNotBlank()) {
                                    DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_$page", pResp.body, "json")
                                    StoreEngineHelpers.recordRawPayload(
                                        database = database,
                                        internalDatabase = internalDatabase,
                                        id = UUID.randomUUID().toString(),
                                        store = "ajio_master_${target.name.replace(" ", "_")}_page_$page",
                                        json = pResp.body,
                                    )
                                    val pParsed = AjioNativeParser.parse(pResp.body, bullionRate24)
                                    val pDiscovered = pParsed.candidates.size
                                    val pSaved = StoreEngineHelpers.saveCandidates(database, storeKey, pParsed.candidates, pincode, distinctPids)

                                    targetDiscovered += pDiscovered
                                    targetValid += pSaved
                                    discovered += pDiscovered
                                    valid += pSaved

                                    activityRepository?.log(
                                        RefreshLogSeverity.Info,
                                        storeKey,
                                        "[AJIO] DISCOVERING page=$page HTTP status=200 discovered=$pDiscovered accepted=$pSaved"
                                    )

                                    if (pDiscovered == 0) {
                                        break
                                    }
                                } else {
                                    lastError = "HTTP ${pResp.status}"
                                    activityRepository?.log(
                                        RefreshLogSeverity.Warning,
                                        storeKey,
                                        "[AJIO] page=$page returned HTTP ${pResp.status}"
                                    )
                                    break
                                }
                            }
                        }

                        val urlElapsed = System.currentTimeMillis() - urlStart
                        activityRepository?.log(
                            RefreshLogSeverity.Info,
                            storeKey,
                            "[AJIO] Target ${target.name} complete: $targetDiscovered discovered, $targetValid valid (${urlElapsed}ms)"
                        )
                    } else {
                        lastError = "HTTP ${resp0.status}"
                        activityRepository?.log(
                            RefreshLogSeverity.Warning,
                            storeKey,
                            "[AJIO] ${target.name} returned HTTP ${resp0.status}"
                        )
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(tag, "Ajio refresh error for ${target.name}: ${e.message}", e)
                    activityRepository?.log(
                        RefreshLogSeverity.Error,
                        storeKey,
                        "[AJIO] ${target.name} failed: ${e.message}"
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
                        is403Encountered = isBlocked || (pdpResult.failed > 0 && lastError?.contains("403") == true),
                        durationMs = duration,
                    )
                )
            }

            if (isBlocked) {
                activityRepository?.log(
                    RefreshLogSeverity.Warning,
                    storeKey,
                    "[AJIO] BLOCKED / PARTIAL_SUCCESS: Existing catalogue preserved (${duration}ms)",
                )
            } else if (valid > 0 || discovered > 0) {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    storeKey,
                    "[AJIO] SUCCESS: totalDiscovered=$discovered, totalAccepted=$valid ingested (${duration}ms)",
                )
            } else {
                activityRepository?.log(
                    RefreshLogSeverity.Info,
                    storeKey,
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
            activityRepository?.log(RefreshLogSeverity.Error, storeKey, "[AJIO] Engine error: $errStr")
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
