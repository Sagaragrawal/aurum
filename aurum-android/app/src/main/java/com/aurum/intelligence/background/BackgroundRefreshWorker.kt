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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aurum.intelligence.AurumApplication
import com.aurum.intelligence.MainActivity

class BackgroundRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as AurumApplication
        return runCatching {
            application.settingsRepository.markBackgroundRefreshRequested()
            val notificationPosted = notifyOpenAurum()
            application.refreshActivityRepository.log(
                if (notificationPosted) com.aurum.intelligence.data.RefreshLogSeverity.Info else com.aurum.intelligence.data.RefreshLogSeverity.Warning,
                null,
                if (notificationPosted) "Scheduled refresh reminder sent" else "Scheduled refresh reminder is waiting; notification permission is unavailable",
            )
            Result.success()
        }.getOrElse { failure ->
            application.refreshActivityRepository.log(
                com.aurum.intelligence.data.RefreshLogSeverity.Error,
                null,
                "Scheduled refresh reminder failed: ${failure.message ?: "unknown error"}",
            )
            Result.retry()
        }
    }

    private fun notifyOpenAurum(): Boolean {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background refresh",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Prompts when Aurum needs an in-app browser collection"
            },
        )
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Aurum refresh is ready")
                .setContentText("Open Aurum to collect current prices in the in-app browser.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
            return true
    }

    private companion object {
        const val CHANNEL_ID = "aurum_background_refresh"
        const val NOTIFICATION_ID = 4101
    }
}