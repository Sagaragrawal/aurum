package com.aurum.intelligence

import android.app.Application
import com.aurum.intelligence.background.BackgroundRefreshScheduler
import com.aurum.intelligence.bridge.LoopbackBridgeServer
import com.aurum.intelligence.data.AppSettingsRepository
import com.aurum.intelligence.data.AurumDatabase
import com.aurum.intelligence.data.BridgeRepository
import com.aurum.intelligence.data.BullionRepository
import com.aurum.intelligence.data.CronetNetworkClient
import com.aurum.intelligence.data.DatabaseBackupManager
import com.aurum.intelligence.data.LocationHelper
import com.aurum.intelligence.data.WatchlistRepository
import com.aurum.intelligence.data.createWatchlistRepository
import com.aurum.intelligence.data.DesktopProductSeeder
import com.aurum.intelligence.data.DesktopBullionHistorySeeder
import com.aurum.intelligence.data.RefreshActivityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AurumApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var database: AurumDatabase
        private set
    lateinit var repository: BridgeRepository
        private set
    lateinit var settingsRepository: AppSettingsRepository
        private set
    lateinit var watchlistRepository: WatchlistRepository
        private set
    lateinit var bullionRepository: BullionRepository
        private set
    lateinit var refreshActivityRepository: RefreshActivityRepository
        private set
    lateinit var nativeParallelRefreshEngine: com.aurum.intelligence.data.NativeParallelRefreshEngine
        private set
    private var bridgeServer: LoopbackBridgeServer? = null
    private val mutableStartupState = MutableStateFlow<StartupState>(StartupState.Starting)
    val startupState = mutableStartupState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        initialize()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }

    fun retryInitialization() {
        initialize()
    }

    private fun initialize() {
        mutableStartupState.value = StartupState.Starting
        runCatching {
            CronetNetworkClient.initialize(this)
            database = AurumDatabase.create(this)
            repository = BridgeRepository(database)
            watchlistRepository = database.createWatchlistRepository()
            bullionRepository = BullionRepository(database)
            refreshActivityRepository = RefreshActivityRepository(database)
            settingsRepository = AppSettingsRepository(this)
            nativeParallelRefreshEngine = com.aurum.intelligence.data.NativeParallelRefreshEngine(
                database = database,
                activityRepository = refreshActivityRepository,
            )
            applicationScope.launch {
                runCatching {
                    // Check if database was cleared and restore from persistent external backup
                    val restoredResult = DatabaseBackupManager.checkAndRestoreIfNeeded(database, repository, this@AurumApplication)
                    if (restoredResult != null) {
                        refreshActivityRepository.log(
                            com.aurum.intelligence.data.RefreshLogSeverity.Info,
                            null,
                            "Restored ${restoredResult.productsAdded + restoredResult.productsMerged} products from persistent backup",
                        )
                    }

                    // Automatic GPS Location & Pincode Resolution on startup
                    if (LocationHelper.hasLocationPermission(this@AurumApplication)) {
                        val details = LocationHelper.detectGpsLocationDetails(this@AurumApplication)
                        if (details != null && details.pincode.matches(Regex("\\d{6}"))) {
                            settingsRepository.setLocation(details.pincode, details.address.orEmpty())
                            if (details.latitude != null && details.longitude != null) {
                                settingsRepository.setCoordinates(details.latitude, details.longitude)
                            }
                        }
                    }

                    bullionRepository.ensureSources()
                    DesktopBullionHistorySeeder(this@AurumApplication, database).seed()
                    DesktopProductSeeder(database, assets).seedIfEmpty()

                    // Sanitize all existing database product entries on startup
                    val allProducts = database.dao().allProducts()
                    var cleanedCount = 0
                    allProducts.forEach { product ->
                        val cleanName = com.aurum.intelligence.data.DatabaseSanitizerEngine.cleanTitle(product.name)
                        val resolvedKarat = com.aurum.intelligence.data.DatabaseSanitizerEngine.resolveKarat(cleanName, product.karat)
                        val resolvedPurity = com.aurum.intelligence.data.DatabaseSanitizerEngine.resolvePurity(cleanName, product.purity)
                        val extractedWeight = com.aurum.intelligence.data.WeightExtractor.parse(cleanName)
                        val unitGrams = extractedWeight.unitWeightGrams ?: product.unitWeightGrams ?: product.grams
                        val totalGrams = extractedWeight.totalWeightGrams ?: product.totalWeightGrams ?: product.grams
                        val isMicro = com.aurum.intelligence.data.DatabaseSanitizerEngine.isMicroCoin(totalGrams)

                        if (cleanName != product.name || resolvedKarat != product.karat || resolvedPurity != product.purity || totalGrams != product.totalWeightGrams || isMicro != product.isMicroCoin) {
                            database.dao().upsertProduct(product.copy(
                                name = cleanName,
                                karat = resolvedKarat,
                                purity = resolvedPurity,
                                unitWeightGrams = unitGrams,
                                quantity = extractedWeight.quantity,
                                totalWeightGrams = totalGrams,
                                grams = totalGrams,
                                weightConfidence = extractedWeight.confidence.name,
                                isMicroCoin = isMicro,
                            ))
                            cleanedCount++
                        }
                    }
                    if (cleanedCount > 0) {
                        refreshActivityRepository.log(
                            com.aurum.intelligence.data.RefreshLogSeverity.Info,
                            null,
                            "DatabaseSanitizerEngine cleaned $cleanedCount product titles, weights, and karat values in database",
                        )
                    }
                }.onFailure { failure ->
                    mutableStartupState.value = StartupState.Degraded(
                        "Aurum opened, but bundled data could not be loaded: ${failure.message ?: "seed error"}",
                    )
                }
            }
            applicationScope.launch {
                settingsRepository.settings
                    .map { settings -> settings.backgroundRefreshEnabled to settings.refreshIntervalMinutes }
                    .distinctUntilChanged()
                    .collect { (enabled, interval) ->
                        BackgroundRefreshScheduler.apply(
                            this@AurumApplication,
                            com.aurum.intelligence.data.AppSettings(
                                backgroundRefreshEnabled = enabled,
                                refreshIntervalMinutes = interval,
                            ),
                        )
                    }
            }
            mutableStartupState.value = StartupState.Ready
        }.onFailure { failure ->
            mutableStartupState.value = StartupState.Failed(failure.message ?: "Unable to open Aurum data")
        }
    }
}

sealed interface StartupState {
    data object Starting : StartupState
    data object Ready : StartupState
    data class Degraded(val message: String) : StartupState
    data class Failed(val message: String) : StartupState
}
