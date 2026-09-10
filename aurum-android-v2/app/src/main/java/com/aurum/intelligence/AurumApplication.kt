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
            internalDatabase = runCatching {
                com.aurum.intelligence.data.db.AurumInternalDatabase.create(this)
            }.getOrElse { e ->
                android.util.Log.w("AurumApp", "Internal database corrupted, re-creating: ${e.message}")
                deleteDatabase("aurum_internal.db")
                com.aurum.intelligence.data.db.AurumInternalDatabase.create(this)
            }
            val dbPath = getDatabasePath("aurum.db")
            val backupFile = DatabaseBackupManager.getBackupFile(this)
            if (!dbPath.exists() && backupFile.exists() && backupFile.length() > 0) {
                runCatching {
                    dbPath.parentFile?.mkdirs()
                    backupFile.copyTo(dbPath, overwrite = true)
                    val wal = java.io.File(backupFile.parentFile, "${backupFile.name}-wal")
                    if (wal.exists()) wal.copyTo(java.io.File(dbPath.parentFile, "aurum.db-wal"), overwrite = true)
                    val shm = java.io.File(backupFile.parentFile, "${backupFile.name}-shm")
                    if (shm.exists()) shm.copyTo(java.io.File(dbPath.parentFile, "aurum.db-shm"), overwrite = true)
                    android.util.Log.i("AurumApp", "Restored existing external aurum.db backup on new installation")
                }
            }
            database = runCatching {
                AurumDatabase.create(this)
            }.getOrElse { e ->
                android.util.Log.w("AurumApp", "Main database corrupted, re-creating: ${e.message}")
                deleteDatabase("aurum.db")
                AurumDatabase.create(this)
            }
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

                    // Clear any old dummy manual timestamps (< 1788800000000) so multi-pack weights can update properly
                    database.openHelper.writableDatabase.execSQL("UPDATE products SET manuallyEditedAt = NULL WHERE manuallyEditedAt IS NOT NULL AND manuallyEditedAt < 1788800000000")

                    // Ensure 100% 24K compliance: purge any non-24K products that may have existed from prior dirty installs
                    database.openHelper.writableDatabase.execSQL("DELETE FROM products WHERE karat != 24.0 OR karat IS NULL")
                    database.openHelper.writableDatabase.execSQL("DELETE FROM product_price_history WHERE productId NOT IN (SELECT id FROM products)")

                    // Run sanitizer to purge any non-gold/22K products
                    DatabaseSanitizerEngine.purgeNon24KGoldCoinsAndBars(database)

                    // Reconcile stale unrefreshed products to unavailable so they don't pollute NotLive
                    database.openHelper.writableDatabase.execSQL("UPDATE products SET status = 'unavailable', deliverable = 0 WHERE status = 'stale'")
                    DatabaseBackupManager.vacuumAndShrinkDatabases(this@AurumApplication, database, internalDatabase)
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
