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
                    Log.i("RefreshBroadcast", "CLEAN_24K executed: deleted $deleted non-24K products, updated /sdcard/Aurum/aurum.db")
                    return@launch
                }

                // Automatic cleanup of non-24K coins before refresh to focus on 24K
                val deleted = app.database.dao().deleteNon24KProducts()
                if (deleted > 0) {
                    Log.i("RefreshBroadcast", "Pre-refresh cleanup: purged $deleted non-24K products from database")
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
                Log.i("RefreshBroadcast", "refreshAllParallel completed successfully")
            } catch (e: Exception) {
                Log.e("RefreshBroadcast", "Error during refresh broadcast execution", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
