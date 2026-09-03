package com.branchdam.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.receiver.ImportConfirmationReceiver
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object ImportConfirmationNotifier {

    const val CHANNEL_ID = "branchdam_import_channel"
    const val NOTIFICATION_ID = 4101
    const val KEY_AUTO_IMPORT = BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL

    const val ACTION_IMPORT_NOW = "com.branchdam.mobile.action.IMPORT_NOW"
    const val ACTION_LATER = "com.branchdam.mobile.action.LATER"
    const val ACTION_SKIP = "com.branchdam.mobile.action.SKIP"

    const val EXTRA_ITEM_COUNT = "extra_item_count"
    const val EXTRA_ITEM_IDS = "extra_item_ids"

    private val suppressedIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingItemsMap = ConcurrentHashMap<String, MediaItem>()

    fun getAutoImportEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SyncScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_IMPORT, false)
    }

    fun setAutoImportEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(SyncScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_IMPORT, enabled).apply()
    }

    fun isItemSuppressed(localId: String): Boolean {
        return suppressedIds.contains(localId)
    }

    fun suppressItem(localId: String) {
        suppressedIds.add(localId)
    }

    fun suppressItems(localIds: Collection<String>) {
        suppressedIds.addAll(localIds)
    }

    fun clearSuppressed() {
        suppressedIds.clear()
    }

    fun stagePendingItems(items: List<MediaItem>) {
        for (item in items) {
            pendingItemsMap[item.contentUri] = item
        }
    }

    fun getPendingItems(): List<MediaItem> {
        return pendingItemsMap.values.toList()
    }

    fun clearPendingItems() {
        pendingItemsMap.clear()
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "branchDAM Import Confirmations"
            val descriptionText = "Notifications to confirm camera roll photo imports"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun showImportConfirmation(
        context: Context,
        newItemCount: Int,
        itemIds: Array<String> = emptyArray()
    ) {
        createNotificationChannel(context)

        val allStagedIds = (itemIds.toList() + pendingItemsMap.keys).distinct().toTypedArray()
        val totalCount = if (allStagedIds.isNotEmpty()) allStagedIds.size else newItemCount

        val importIntent = Intent(context, ImportConfirmationReceiver::class.java).apply {
            action = ACTION_IMPORT_NOW
            putExtra(EXTRA_ITEM_COUNT, totalCount)
            putExtra(EXTRA_ITEM_IDS, allStagedIds)
        }
        val laterIntent = Intent(context, ImportConfirmationReceiver::class.java).apply {
            action = ACTION_LATER
            putExtra(EXTRA_ITEM_COUNT, totalCount)
            putExtra(EXTRA_ITEM_IDS, allStagedIds)
        }
        val skipIntent = Intent(context, ImportConfirmationReceiver::class.java).apply {
            action = ACTION_SKIP
            putExtra(EXTRA_ITEM_COUNT, totalCount)
            putExtra(EXTRA_ITEM_IDS, allStagedIds)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val importPending = PendingIntent.getBroadcast(context, 101, importIntent, flags)
        val laterPending = PendingIntent.getBroadcast(context, 102, laterIntent, flags)
        val skipPending = PendingIntent.getBroadcast(context, 103, skipIntent, flags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("New Photos Detected")
            .setContentText("$totalCount new photo(s) ready to import to branchDAM")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_upload, "Import now", importPending)
            .addAction(android.R.drawable.ic_menu_recent_history, "Later", laterPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skipPending)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    fun handleAction(context: Context?, action: String?, itemIds: Array<String> = emptyArray()) {
        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)

        val targetIds = if (itemIds.isNotEmpty()) itemIds.toSet() else pendingItemsMap.keys

        when (action) {
            ACTION_IMPORT_NOW -> {
                for (id in targetIds) {
                    val item = pendingItemsMap.remove(id)
                    if (item != null && !isItemSuppressed(id)) {
                        EngineHolder.enqueueMedia(
                            localPath = item.filePath,
                            filename = item.displayName,
                            capturedAtUnix = item.dateTakenUnix,
                            localId = item.contentUri
                        )
                    }
                }
                if (context != null) {
                    SyncScheduler.triggerImmediateSync(context)
                }
            }
            ACTION_LATER -> {
                for (id in targetIds) {
                    val item = pendingItemsMap.remove(id)
                    if (item != null && !isItemSuppressed(id)) {
                        EngineHolder.enqueueMedia(
                            localPath = item.filePath,
                            filename = item.displayName,
                            capturedAtUnix = item.dateTakenUnix,
                            localId = item.contentUri
                        )
                    }
                }
                if (context != null) {
                    SyncScheduler.schedulePeriodicSync(context)
                }
            }
            ACTION_SKIP -> {
                suppressItems(targetIds)
                for (id in targetIds) {
                    pendingItemsMap.remove(id)
                }
            }
        }
    }
}
