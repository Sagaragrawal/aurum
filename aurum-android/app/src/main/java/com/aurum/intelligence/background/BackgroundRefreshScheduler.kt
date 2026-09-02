package com.aurum.intelligence.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aurum.intelligence.data.AppSettings
import java.util.concurrent.TimeUnit

object BackgroundRefreshScheduler {
    const val UNIQUE_WORK_NAME = "aurum-background-refresh"
    const val MINIMUM_INTERVAL_MINUTES = 15

    fun apply(context: Context, settings: AppSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.backgroundRefreshEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val interval = settings.refreshIntervalMinutes.coerceAtLeast(MINIMUM_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<BackgroundRefreshWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}