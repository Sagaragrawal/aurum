package com.aurum.intelligence.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aurum.intelligence.AurumApplication
import com.aurum.intelligence.data.DatabaseBackupManager
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
                    val pincode = intent.getStringExtra("pincode") ?: settings.pincode.takeIf { it.isNotBlank() } ?: "560048"
                    Log.i("RefreshBroadcast", "Running Standalone 22K Audit with pincode=$pincode...")
                    val report = com.aurum.intelligence.data.Standalone22KEngine.audit22kAcrossStores(pincode)
                    Log.i("RefreshBroadcast", "AUDIT_22K Result: total22k=${report.total22kProducts}, requiringPdp=${report.totalRequiringPdp}, duration=${report.totalDurationMs}ms")
                    report.storeReports.forEach { (store, rep) ->
                        Log.i("RefreshBroadcast", "[$store 22K] found=${rep.productsFound}, completePlp=${rep.itemsWithCompletePlpData}, pdpNeeded=${rep.itemsRequiringPdp}, err=${rep.error}")
                    }
                    return@launch
                }

                // Automatic cleanup of non-24K coins before refresh to focus on 24K
                val deletedSql = app.database.dao().deleteNon24KProducts()
                val deletedPurge = com.aurum.intelligence.data.DatabaseSanitizerEngine.purgeNon24KGoldCoinsAndBars(app.database)
                if (deletedSql > 0 || deletedPurge > 0) {
                    Log.i("RefreshBroadcast", "Pre-refresh cleanup: purged ${deletedSql + deletedPurge} non-24K products from database")
                }

                val settings = app.settingsRepository.settings.first()
                val pincode = intent.getStringExtra("pincode") ?: settings.pincode.takeIf { it.isNotBlank() } ?: "560048"
                val maxPages = intent.getIntExtra("maxPages", 10)
                Log.i("RefreshBroadcast", "Starting refreshAllParallel with pincode=$pincode, maxPages=$maxPages")
                app.nativeParallelRefreshEngine.refreshAllParallel(
                    pincode = pincode,
                    latitude = settings.latitude,
                    longitude = settings.longitude,
                    maxPagesPerStore = maxPages,
                )
                DatabaseBackupManager.createBackup(app.repository, app)
                Log.i("RefreshBroadcast", "refreshAllParallel completed successfully; backup updated to /sdcard/Aurum/database/aurum.db")
            } catch (e: Exception) {
                Log.e("RefreshBroadcast", "Error during refresh broadcast execution", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
