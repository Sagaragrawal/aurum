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
import com.aurum.intelligence.parsers.MyntraNativeParser
import java.util.UUID
import kotlinx.coroutines.delay

class MyntraScraperEngine(
    private val database: AurumDatabase,
    private val internalDatabase: AurumInternalDatabase? = null,
    private val activityRepository: RefreshActivityRepository? = null,
    private val context: Context? = null,
) : StoreScraperEngine {

    override val storeKey: String = "myntra.com"
    private val tag = "MyntraScraperEngine"

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
            val gatewayHeaders = config.stores["myntra"]?.gatewayHeaders ?: emptyMap()
            val webHeaders = config.stores["myntra"]?.webHeaders ?: config.network.desktopHeaders
            val myntraTargets = config.myntraTargets

            for (target in myntraTargets) {
                val urlStart = System.currentTimeMillis()
                try {
                    activityRepository?.log(
                        RefreshLogSeverity.Info,
                        storeKey,
                        "[Myntra] Establishing session for ${target.name}..."
                    )
                    plpRequests++
                    val initResp = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, target.url, webHeaders, activityRepository = activityRepository)

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
                    val pageSize = config.limits.myntraPageSize.coerceAtLeast(10)
                    val page1ApiUrl = "${gatewayBase}$categorySlug?rows=$pageSize&o=0&plaEnabled=true&xdEnabled=false&isFacet=true&p=1&pincode=$pincode"
                    plpRequests++
                    var pageResp = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, page1ApiUrl, sessionGatewayHeaders, activityRepository = activityRepository)
                    if (pageResp.status != 200) {
                        pageResp = initResp
                    }

                    if (pageResp.status in 200..299 && pageResp.body.isNotBlank()) {
                        DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_1", pageResp.body, if (pageResp.body.trimStart().startsWith("<")) "html" else "json")
                        StoreEngineHelpers.recordRawPayload(
                            database = database,
                            internalDatabase = internalDatabase,
                            id = UUID.randomUUID().toString(),
                            store = "myntra_master_${target.name.replace(" ", "_")}_page_1",
                            json = pageResp.body,
                        )
                        var currentPaginationCtx = pageResp.headers["pagination-context"]?.firstOrNull()
                            ?: pageResp.headers["Pagination-Context"]?.firstOrNull()

                        val parsed = MyntraNativeParser.parse(pageResp.body, bullionRate24)
                        val s0 = StoreEngineHelpers.saveCandidates(database, storeKey, parsed.candidates, pincode, distinctPids)
                        targetDiscovered += parsed.candidates.size
                        targetValid += s0
                        discovered += parsed.candidates.size
                        valid += s0

                        val seenIds = parsed.candidates.map { it.retailerId }.toMutableSet()
                        val totalAvailable = parsed.totalCount
                        val totalPagesAvailable = if (totalAvailable > 0) (totalAvailable + pageSize - 1) / pageSize else config.limits.maxPagesPerStore
                        val pageLimit = totalPagesAvailable

                        if (pageLimit > 1) {
                            for (page in 2..pageLimit) {
                                delay(config.delays.myntraApiPageDelayMs)
                                val offset = (page - 1) * pageSize
                                val nextApiUrl = "${gatewayBase}$categorySlug?rows=$pageSize&o=$offset&plaEnabled=true&xdEnabled=false&isFacet=true&p=$page&pincode=$pincode"
                                val nextHeaders = if (!currentPaginationCtx.isNullOrBlank()) {
                                    sessionGatewayHeaders + ("pagination-context" to currentPaginationCtx)
                                } else sessionGatewayHeaders

                                plpRequests++
                                activityRepository?.log(
                                    RefreshLogSeverity.Info,
                                    storeKey,
                                    "[Myntra] Fetching ${target.name} (page $page of $pageLimit)..."
                                )
                                var r = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, nextApiUrl, nextHeaders, activityRepository = activityRepository)
                                if (r.status != 200) {
                                    val pageSep = if (target.url.contains("?")) "&" else "?"
                                    val nextWebUrl = "${target.url}${pageSep}p=$page"
                                    r = StoreEngineHelpers.fetchStorePageWithRecovery(storeKey, nextWebUrl, webHeaders, activityRepository = activityRepository)
                                }

                                val newCtx = r.headers["pagination-context"]?.firstOrNull()
                                    ?: r.headers["Pagination-Context"]?.firstOrNull()
                                if (!newCtx.isNullOrBlank()) currentPaginationCtx = newCtx

                                if (r.status in 200..299) {
                                    DatabaseBackupManager.saveRawPage(storeKey, "${target.name}_page_$page", r.body, if (r.body.trimStart().startsWith("<")) "html" else "json")
                                    StoreEngineHelpers.recordRawPayload(
                                        database = database,
                                        internalDatabase = internalDatabase,
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
                            "[Myntra] ${target.name}: $targetDiscovered discovered, $targetValid valid saved (${urlElapsed}ms)"
                        )
                    } else {
                        activityRepository?.log(
                            RefreshLogSeverity.Warning,
                            storeKey,
                            "[Myntra] ${target.name} page 1 returned HTTP ${pageResp.status}"
                        )
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(tag, "Myntra refresh error for ${target.name}: ${e.message}", e)
                    activityRepository?.log(
                        RefreshLogSeverity.Error,
                        storeKey,
                        "[Myntra] ${target.name} failed: ${e.message}"
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
            val errStr = e.message ?: "Myntra error"
            Log.e(tag, "Myntra engine error: $errStr", e)
            activityRepository?.log(RefreshLogSeverity.Error, storeKey, "[Myntra] Engine error: $errStr")
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
