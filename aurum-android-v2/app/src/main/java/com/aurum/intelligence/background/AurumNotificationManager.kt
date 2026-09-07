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
import com.aurum.intelligence.MainActivity
import java.util.concurrent.ConcurrentHashMap

object AurumNotificationManager {
    private val channelDealsId: String get() = ScraperConfigProvider.get().notifications.channelDealsId
    private val channelDealsName: String get() = ScraperConfigProvider.get().notifications.channelDealsName
    private val channelRefreshId: String get() = ScraperConfigProvider.get().notifications.channelRefreshId
    private val channelRefreshName: String get() = ScraperConfigProvider.get().notifications.channelRefreshName
    private val deduplicationWindowMs: Long get() = ScraperConfigProvider.get().notifications.deduplicationWindowMs

    private val notifiedDealsCache = ConcurrentHashMap<String, Long>()

    fun shouldNotifyDeal(dealKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val lastNotified = notifiedDealsCache[dealKey]
        if (lastNotified != null && (nowMillis - lastNotified) < deduplicationWindowMs) {
            return false
        }
        notifiedDealsCache[dealKey] = nowMillis
        return true
    }

    fun notifyDealAlert(
        context: Context,
        title: String,
        message: String,
        dealKey: String? = null,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        if (dealKey != null && !shouldNotifyDeal(dealKey)) return false

        ensureChannelsCreated(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelDealsId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    fun notifyBlinkDeal(context: Context, productName: String, priceText: String, store: String): Boolean {
        val title = "⚡ Blink Deal Started on $store!"
        val message = "$productName is available now for $priceText!"
        val dealKey = "blink_${store}_$productName"
        return notifyDealAlert(context, title, message, dealKey = dealKey)
    }

    fun notifyBelowBullionDeal(context: Context, productName: String, effectivePerGram: String, bullionRate: String): Boolean {
        val title = "🔥 Steal Deal: Below Bullion Rate!"
        val message = "$productName @ $effectivePerGram/g (Bullion rate: $bullionRate/g)"
        val dealKey = "steal_${productName}_$effectivePerGram"
        return notifyDealAlert(context, title, message, dealKey = dealKey)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val dealsChannel = NotificationChannel(
            channelDealsId,
            channelDealsName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts for Blink Deals and products below bullion rates"
            enableVibration(true)
        }
        val refreshChannel = NotificationChannel(
            channelRefreshId,
            channelRefreshName,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Prompts when Aurum needs an in-app browser collection"
        }
        manager.createNotificationChannel(dealsChannel)
        manager.createNotificationChannel(refreshChannel)
    }
}
