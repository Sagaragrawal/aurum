package com.aurum.intelligence.background

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
        Log.i("RefreshBroadcast", "Received refresh broadcast action=${intent.action}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
