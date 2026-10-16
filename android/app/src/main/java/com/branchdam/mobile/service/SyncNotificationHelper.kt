package com.branchdam.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.branchdam.mobile.MainActivity
import com.branchdam.mobile.R

object SyncNotificationHelper {

    const val CHANNEL_ID = "branchdam_sync_channel"
    const val NOTIFICATION_ID = 4201

    const val CHANNEL_NAME = "branchDAM Sync"
    const val CHANNEL_DESCRIPTION = "Background sync progress"
    const val NOTIFICATION_TITLE = "Syncing camera roll"
    const val NOTIFICATION_TEXT = "Syncing camera roll to branchDAM server"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(context: Context): android.app.Notification {
        ensureChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pending)
            .build()
    }

    const val REPAIR_CHANNEL_ID = "branchdam_repair_channel"
    const val REPAIR_NOTIFICATION_ID = 4202

    /**
     * Tells the user the server refused this device's credentials (core
     * error AUTH_FAILED: key revoked or rotated, pairing removed). Unlike
     * a network error this won't clear on its own, so it is a separate,
     * dismissable notification on its own default-importance channel.
     */
    fun showRepairRequired(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(REPAIR_CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    REPAIR_CHANNEL_ID,
                    context.getString(R.string.repair_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            REPAIR_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val repairText = context.getString(R.string.repair_notification_text)
        val notification = NotificationCompat.Builder(context, REPAIR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.repair_notification_title))
            .setContentText(repairText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(repairText))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
        manager.notify(REPAIR_NOTIFICATION_ID, notification)
    }

    /** Removes the [showRepairRequired] notification (after a successful sync). */
    fun cancelRepairRequired(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        manager.cancel(REPAIR_NOTIFICATION_ID)
    }

    fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }
}
