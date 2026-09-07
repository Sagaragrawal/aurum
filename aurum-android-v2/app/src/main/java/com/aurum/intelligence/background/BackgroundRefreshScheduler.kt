package com.aurum.intelligence.background
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundRefreshScheduler {
    val uniqueWorkName: String get() = ScraperConfigProvider.get().notifications.backgroundWorkName
    val minimumIntervalMinutes: Int get() = ScraperConfigProvider.get().appSettingsDefaults.allowedRefreshIntervals.minOrNull() ?: 15

    fun apply(context: Context, settings: AppSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.backgroundRefreshEnabled) {
            workManager.cancelUniqueWork(uniqueWorkName)
            return
        }
        val interval = settings.refreshIntervalMinutes.coerceAtLeast(minimumIntervalMinutes).toLong()
        val request = PeriodicWorkRequestBuilder<BackgroundRefreshWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}