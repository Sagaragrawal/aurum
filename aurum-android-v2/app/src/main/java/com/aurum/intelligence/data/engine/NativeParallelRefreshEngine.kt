package com.aurum.intelligence.data.engine

import android.content.Context
import android.util.Log
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.stores.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.parsers.BullionNativeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val context: Context? = null,
) {
    private val tag = "ParallelRefreshEngine"

    private val storeEngines: Map<String, StoreScraperEngine> = mapOf(
        "ajio.com" to AjioScraperEngine(database, internalDatabase, activityRepository, context),
        "amazon.in" to AmazonScraperEngine(database, internalDatabase, activityRepository, context),
        "flipkart.com" to FlipkartScraperEngine(database, internalDatabase, activityRepository, context),
        "shopsy.in" to ShopsyScraperEngine(database, internalDatabase, activityRepository, context),
        "myntra.com" to MyntraScraperEngine(database, internalDatabase, activityRepository, context),
    )

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

        runCatching {
            activityRepository?.startNewRun(activeStores)
            if (activeStores == null) {
                DatabaseBackupManager.clearAllRawPages()
            } else {
                for (st in activeStores) {
                    DatabaseBackupManager.clearStoreRawPages(st)
                }
            }
        }

        activityRepository?.log(RefreshLogSeverity.Info, null, "Starting native parallel refresh for $targetLabel and bullion")

        val initialBullion = database.dao().latestBullionHistory()
        val initialBenchmarkRate = initialBullion?.price24

        val (storeResults, bullionRates) = coroutineScope {
            val deferreds = storeEngines.mapNotNull { (storeKey, engine) ->
                if (activeStores == null || storeKey in activeStores) {
                    async { engine.refreshStore(pincode, initialBenchmarkRate, maxPagesPerStore, onProgress) }
                } else null
            }

            val bullionDeferred = async { refreshBullion() }

            val stores = deferreds.awaitAll()
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

        runCatching { database.dao().markAllStaleProductsUnavailable() }

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
}
