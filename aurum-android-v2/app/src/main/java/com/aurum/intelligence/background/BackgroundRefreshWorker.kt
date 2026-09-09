package com.aurum.intelligence.background
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

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
import com.aurum.intelligence.ui.DealMode
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
        val config = ScraperConfigProvider.get()
        val channelId = config.notifications.channelRefreshId
        val notificationId = config.notifications.notificationRefreshId
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, config.notifications.channelRefreshName, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Aurum Background Refresh")
            .setContentText("Checking for deals and updated prices...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as AurumApplication
        try {
            runCatching { setForeground(getForegroundInfo()) }

            val settings = application.settingsRepository.settings.first()
            val pincode = settings.pincode.takeIf { it.isNotBlank() } ?: ScraperConfigProvider.get().location.defaultPincode

            // 100% Native Parallel Refresh for All 5 Stores and All 4 Bullions
            application.nativeParallelRefreshEngine.refreshAllParallel(
                pincode = pincode,
                latitude = settings.latitude,
                longitude = settings.longitude,
                maxPagesPerStore = ScraperConfigProvider.get().limits.defaultPagesPerRefresh,
            )

            // 3. Scan deals and notify for Blink Deals, below bullion, or matching threshold
            val products = application.database.dao().allProducts()
            val bullionSources = application.database.dao().allBullionSources()
            val clean24 = BullionBenchmark.cleanRates(bullionSources.mapNotNull { it.price24 })
            val clean22 = BullionBenchmark.cleanRates(bullionSources.mapNotNull { it.price22 })
            val benchmark24 = BullionBenchmark.blend(clean24)
            val benchmark22 = BullionBenchmark.blend(clean22)

            val mode = runCatching { DealMode.valueOf(settings.dealMode) }.getOrDefault(DealMode.Percent)
            val threshold = if (mode == DealMode.Percent) settings.dealPercentThreshold else settings.dealRupeesThreshold

            var blinkDealsFound = 0
            var dealsFound = 0

            products.forEach { product ->
                if (product.isBlinkDeal && product.blinkDealPrice != null) {
                    blinkDealsFound++
                    AurumNotificationManager.notifyBlinkDeal(
                        applicationContext,
                        ProductCalculations.displayName(product),
                        "₹${product.blinkDealPrice.toInt()}",
                        product.store,
                    )
                }

                val benchmark = ProductCalculations.benchmarkFor(product, benchmark24, benchmark22)
                if (benchmark != null && ProductCalculations.isDealEligible(product, benchmark, System.currentTimeMillis())) {
                    val effectivePerGram = ProductCalculations.effectivePerGram(product)
                    if (effectivePerGram != null) {
                        val rupeesDelta = effectivePerGram - benchmark
                        val percentDelta = (rupeesDelta / benchmark) * 100
                        val deltaVal = if (mode == DealMode.Percent) percentDelta else rupeesDelta

                        if (rupeesDelta < 0 || kotlin.math.abs(deltaVal) <= threshold.coerceAtLeast(0.0)) {
                            dealsFound++
                            val title = if (rupeesDelta < 0) "🔥 Steal Deal: Below Bullion Rate!" else "🎯 Deal Alert: Matches Bullion Threshold"
                            AurumNotificationManager.notifyDealAlert(
                                context = applicationContext,
                                title = title,
                                message = "${ProductCalculations.displayName(product)} @ ₹${effectivePerGram.toInt()}/g (Bullion: ₹${benchmark.toInt()}/g)",
                                dealKey = "deal_${product.id}_${effectivePerGram.toInt()}",
                            )
                        }
                    }
                }
            }

            application.refreshActivityRepository.log(
                com.aurum.intelligence.data.repository.RefreshLogSeverity.Info,
                null,
                "Background scan complete: $blinkDealsFound Blink Deals, $dealsFound Deals found",
            )

            com.aurum.intelligence.data.db.DatabaseBackupManager.createBackup(application.repository, applicationContext)

            Result.success()
        } catch (e: Exception) {
            application.refreshActivityRepository.log(
                com.aurum.intelligence.data.repository.RefreshLogSeverity.Error,
                null,
                "Scheduled background refresh failed: ${e.message}",
            )
            Result.retry()
        }
    }
}
