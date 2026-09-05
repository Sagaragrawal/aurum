package com.aurum.intelligence.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.aurum.intelligence.AurumApplication
import com.aurum.intelligence.MainActivity
import com.aurum.intelligence.ui.ProductCalculations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class BackgroundRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background refresh", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Aurum Background Refresh")
            .setContentText("Checking for deals and updated prices...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val application = applicationContext as AurumApplication
        try {
            setForeground(getForegroundInfo())
            application.settingsRepository.markBackgroundRefreshRequested()

            val settings = application.settingsRepository.settings.first()
            val pincode = settings.pincode ?: "560048"

            // 100% Native Parallel Refresh for All 5 Stores and All 4 Bullions
            application.nativeParallelRefreshEngine.refreshAllParallel(
                pincode = pincode,
                latitude = settings.latitude,
                longitude = settings.longitude,
                maxPagesPerStore = 3,
            )

            // 3. Scan deals
            val products = application.database.dao().allProducts()
            val bullionSources = application.database.dao().allBullionSources()
            val benchmark24 = bullionSources.mapNotNull { it.price24 }.average().takeIf { it > 0 }
            val benchmark22 = bullionSources.mapNotNull { it.price22 }.average().takeIf { it > 0 }

            var blinkDealsFound = 0
            var stealDealsFound = 0

            products.forEach { product ->
                if (product.isBlinkDeal && product.blinkDealPrice != null) {
                    blinkDealsFound++
                    AurumNotificationManager.notifyBlinkDeal(
                        applicationContext,
                        product.name,
                        "₹${product.blinkDealPrice.toInt()}",
                        product.store,
                    )
                }

                val benchmark = ProductCalculations.benchmarkFor(product, benchmark24, benchmark22)
                if (benchmark != null && ProductCalculations.isDealEligible(product, benchmark, System.currentTimeMillis())) {
                    val effectivePerGram = ProductCalculations.effectivePerGram(product)
                    if (effectivePerGram != null && effectivePerGram < benchmark) {
                        stealDealsFound++
                        AurumNotificationManager.notifyBelowBullionDeal(
                            applicationContext,
                            product.name,
                            "₹${effectivePerGram.toInt()}",
                            "₹${benchmark.toInt()}",
                        )
                    }
                }
            }

            application.refreshActivityRepository.log(
                com.aurum.intelligence.data.RefreshLogSeverity.Info,
                null,
                "Background scan complete: $blinkDealsFound Blink Deals, $stealDealsFound Steal Deals",
            )
            
            com.aurum.intelligence.data.DatabaseBackupManager.createBackup(application.repository, applicationContext)

            Result.success()
        } catch (e: Exception) {
            application.refreshActivityRepository.log(
                com.aurum.intelligence.data.RefreshLogSeverity.Error,
                null,
                "Scheduled background refresh failed: ${e.message}",
            )
            Result.retry()
        }
    }

    private companion object {
        const val CHANNEL_ID = "aurum_background_refresh"
        const val NOTIFICATION_ID = 4101
    }
}
