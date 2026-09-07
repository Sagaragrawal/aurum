package com.aurum.intelligence
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.app.Application
import com.aurum.intelligence.background.BackgroundRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AurumApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var database: AurumDatabase
        private set
    lateinit var internalDatabase: com.aurum.intelligence.data.db.AurumInternalDatabase
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
    lateinit var nativeParallelRefreshEngine: com.aurum.intelligence.data.engine.NativeParallelRefreshEngine
        private set
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
            com.aurum.intelligence.data.engine.ScraperConfigProvider.init(this)
            CronetNetworkClient.initialize(this)
            internalDatabase = com.aurum.intelligence.data.db.AurumInternalDatabase.create(this)
            database = AurumDatabase.create(this)
            repository = BridgeRepository(database)
            watchlistRepository = database.createWatchlistRepository()
            bullionRepository = BullionRepository(database)
            refreshActivityRepository = RefreshActivityRepository(internalDatabase)
            settingsRepository = AppSettingsRepository(this)
            nativeParallelRefreshEngine = com.aurum.intelligence.data.engine.NativeParallelRefreshEngine(
                database = database,
                internalDatabase = internalDatabase,
                activityRepository = refreshActivityRepository,
                context = this,
            )
            applicationScope.launch {
                runCatching {
                    // Check if database was cleared and restore from persistent external backup
                    val restoredResult = DatabaseBackupManager.checkAndRestoreIfNeeded(database, repository, this@AurumApplication)
                    if (restoredResult != null) {
                        refreshActivityRepository.log(
                            com.aurum.intelligence.data.repository.RefreshLogSeverity.Info,
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
                        val cleanName = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.cleanTitle(product.name)
                        val resolvedKarat = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.resolveKarat(cleanName, product.karat)
                        val resolvedPurity = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.resolvePurity(cleanName, product.purity)
                        val extractedWeight = com.aurum.intelligence.data.validation.WeightExtractor.parse(cleanName)
                        val unitGrams = extractedWeight.unitWeightGrams ?: product.unitWeightGrams ?: product.grams
                        val totalGrams = extractedWeight.totalWeightGrams ?: product.totalWeightGrams ?: product.grams
                        val isMicro = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.isMicroCoin(totalGrams)

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

                    // Enforce 100% 24K Gold Coin & Bar compliance on startup
                    val purgedCount = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.purgeNon24KGoldCoinsAndBars(database)
                    if (purgedCount > 0) {
                        refreshActivityRepository.log(
                            com.aurum.intelligence.data.repository.RefreshLogSeverity.Info,
                            null,
                            "DatabaseSanitizerEngine purged $purgedCount invalid non-24K/jewelry items from database",
                        )
                    }

                    if (cleanedCount > 0 || purgedCount > 0) {
                        DatabaseBackupManager.createBackup(repository, this@AurumApplication)
                    }

                    // Always sync databases to /storage/emulated/0/aurum/ on startup
                    DatabaseBackupManager.syncDatabasesToExternal(this@AurumApplication, database, internalDatabase)
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
                            com.aurum.intelligence.data.repository.AppSettings(
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
