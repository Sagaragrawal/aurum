package com.aurum.intelligence

import android.app.Application
import com.aurum.intelligence.background.BackgroundRefreshScheduler
import com.aurum.intelligence.bridge.LoopbackBridgeServer
import com.aurum.intelligence.data.AppSettingsRepository
import com.aurum.intelligence.data.AurumDatabase
import com.aurum.intelligence.data.BridgeRepository
import com.aurum.intelligence.data.BullionRepository
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
    private var bridgeServer: LoopbackBridgeServer? = null
    private val mutableStartupState = MutableStateFlow<StartupState>(StartupState.Starting)
    val startupState = mutableStartupState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        initialize()
    }

    fun retryInitialization() {
        if (mutableStartupState.value is StartupState.Failed) initialize()
        else retryBridge()
    }

    private fun initialize() {
        mutableStartupState.value = StartupState.Starting
        runCatching {
            database = AurumDatabase.create(this)
            repository = BridgeRepository(database)
            watchlistRepository = database.createWatchlistRepository()
            bullionRepository = BullionRepository(database)
            refreshActivityRepository = RefreshActivityRepository(database)
            settingsRepository = AppSettingsRepository(this)
            applicationScope.launch {
                runCatching {
                    bullionRepository.ensureSources()
                    DesktopBullionHistorySeeder(this@AurumApplication, database).seed()
                    DesktopProductSeeder(database, assets).seedIfEmpty()
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
            retryBridge()
        }.onFailure { failure ->
            mutableStartupState.value = StartupState.Failed(failure.message ?: "Unable to open Aurum data")
        }
    }

    private fun retryBridge() {
        bridgeServer?.close()
        val server = LoopbackBridgeServer(repository, applicationScope)
        bridgeServer = server
        server.start()
            .onSuccess { mutableStartupState.value = StartupState.Ready }
            .onFailure { failure ->
                mutableStartupState.value = StartupState.Degraded(
                    "Product refresh is unavailable because local port 8788 could not start: ${failure.message ?: "port in use"}",
                )
            }
    }
}

sealed interface StartupState {
    data object Starting : StartupState
    data object Ready : StartupState
    data class Degraded(val message: String) : StartupState
    data class Failed(val message: String) : StartupState
}