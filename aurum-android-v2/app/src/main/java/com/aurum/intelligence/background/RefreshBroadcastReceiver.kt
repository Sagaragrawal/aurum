package com.aurum.intelligence.background
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aurum.intelligence.AurumApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RefreshBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AurumApplication ?: return
        val pendingResult = goAsync()
        Log.i("RefreshBroadcast", "Received broadcast action=${intent.action}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == "com.aurum.intelligence.CLEAN_24K") {
                    val deleted = app.database.dao().deleteNon24KProducts()
                    DatabaseBackupManager.createBackup(app.repository, app)
                    Log.i("RefreshBroadcast", "CLEAN_24K executed: deleted $deleted non-24K products, updated /sdcard/Aurum/database/aurum.db")
                    return@launch
                }

                if (intent.action == "com.aurum.intelligence.AUDIT_22K") {
                    val settings = app.settingsRepository.settings.first()
                    val defaultPincode = ScraperConfigProvider.get().location.defaultPincode
                    val pincode = intent.getStringExtra("pincode") ?: settings.pincode.takeIf { it.isNotBlank() } ?: defaultPincode
                    Log.i("RefreshBroadcast", "Running Standalone 22K Audit with pincode=$pincode...")
                    val report = com.aurum.intelligence.data.engine.Standalone22KEngine.audit22kAcrossStores(pincode)
                    Log.i("RefreshBroadcast", "AUDIT_22K Result: total22k=${report.total22kProducts}, requiringPdp=${report.totalRequiringPdp}, duration=${report.totalDurationMs}ms")
                    report.storeReports.forEach { (store, rep) ->
                        Log.i("RefreshBroadcast", "[$store 22K] found=${rep.productsFound}, completePlp=${rep.itemsWithCompletePlpData}, pdpNeeded=${rep.itemsRequiringPdp}, err=${rep.error}")
                    }
                    return@launch
                }

                // Automatic cleanup of non-24K coins before refresh to focus on 24K
                val deletedSql = app.database.dao().deleteNon24KProducts()
                val deletedPurge = com.aurum.intelligence.data.db.DatabaseSanitizerEngine.purgeNon24KGoldCoinsAndBars(app.database)
                if (deletedSql > 0 || deletedPurge > 0) {
                    Log.i("RefreshBroadcast", "Pre-refresh cleanup: purged ${deletedSql + deletedPurge} non-24K products from database")
                }

                val settings = app.settingsRepository.settings.first()
                val defaultPincode = ScraperConfigProvider.get().location.defaultPincode
                val defaultMaxPages = ScraperConfigProvider.get().limits.defaultPagesPerRefresh
                val pincode = intent.getStringExtra("pincode") ?: settings.pincode.takeIf { it.isNotBlank() } ?: defaultPincode
                val maxPages = intent.getIntExtra("maxPages", defaultMaxPages)
                val targetStore = intent.getStringExtra("store")
                val targetStores = targetStore?.takeIf { it.isNotBlank() }?.let { setOf(it) }

                Log.i("RefreshBroadcast", "Starting refreshAllParallel with pincode=$pincode, maxPages=$maxPages, targetStores=$targetStores")
                app.nativeParallelRefreshEngine.refreshAllParallel(
                    pincode = pincode,
                    latitude = settings.latitude,
                    longitude = settings.longitude,
                    maxPagesPerStore = maxPages,
                    targetStores = targetStores,
                )
                DatabaseBackupManager.createBackup(app.repository, app)
                Log.i("RefreshBroadcast", "refreshAllParallel completed successfully")
            } catch (e: Exception) {
                Log.e("RefreshBroadcast", "Error during refresh broadcast execution", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
