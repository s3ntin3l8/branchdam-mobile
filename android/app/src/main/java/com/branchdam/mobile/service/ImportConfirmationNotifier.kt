package com.branchdam.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.PendingImport
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.observer.UploadStaging
import com.branchdam.mobile.receiver.ImportConfirmationReceiver

/**
 * The "new photos detected" confirmation flow for when auto-import is off.
 *
 * Items awaiting the user's answer, and the items the user skipped, live
 * in the core's pending_import table (via [EngineHolder]), not in memory:
 * "Import now" tapped after the process was killed still finds them, and
 * a skipped item is not offered again on a later scan or after a restart.
 */
object ImportConfirmationNotifier {

    const val CHANNEL_ID = "branchdam_import_channel"
    const val NOTIFICATION_ID = 4101
    const val KEY_AUTO_IMPORT = BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL

    const val ACTION_IMPORT_NOW = "com.branchdam.mobile.action.IMPORT_NOW"
    const val ACTION_LATER = "com.branchdam.mobile.action.LATER"
    const val ACTION_SKIP = "com.branchdam.mobile.action.SKIP"

    /** The notification actions [handleAction] understands. */
    val ACTIONS: Set<String> = setOf(ACTION_IMPORT_NOW, ACTION_LATER, ACTION_SKIP)

    /**
     * The "Importing photos" notification [ImportConfirmationWorker] shows
     * while it runs as a foreground service (expedited work below API 31).
     */
    const val IMPORTING_NOTIFICATION_ID = 4102

    /**
     * Seconds since the epoch at which the notification was posted. An
     * action applies to the pending items staged by then, so "Skip" never
     * suppresses a photo the user was not shown. (Item IDs are not put in
     * the intent: thousands of content URIs could exceed the binder
     * transaction limit.)
     */
    const val EXTRA_STAGED_BEFORE_UNIX = "extra_staged_before_unix"

    fun getAutoImportEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SyncScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_IMPORT, false)
    }

    fun setAutoImportEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(SyncScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_IMPORT, enabled).apply()
    }

    /** Whether the user skipped [localId]. Blocking engine call. */
    fun isItemSuppressed(localId: String): Boolean = EngineHolder.isImportSuppressed(localId)

    /** Records that the user skipped [localId]. Blocking engine call. */
    fun suppressItem(localId: String): Boolean = EngineHolder.suppressPendingImport(localId)

    /**
     * Persists [item] as awaiting the user's decision. Returns
     * [EngineHolder.StageResult.SUPPRESSED] (nothing staged) when the
     * user already skipped it. Blocking engine call.
     */
    fun stagePendingItem(item: MediaItem): EngineHolder.StageResult =
        EngineHolder.stagePendingImport(
            localId = item.contentUri,
            localPath = item.filePath,
            filename = item.displayName,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
            capturedAtUnix = item.dateTakenUnix,
        )

    /** Items awaiting a decision; empty when they can't be read. */
    fun getPendingItems(): List<PendingImport> = EngineHolder.listPendingImports() ?: emptyList()

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

    /**
     * Posts (or replaces) the confirmation notification for the
     * [pendingCount] items awaiting a decision. Callers announce the
     * whole persisted pending set (see [PendingImportAnnouncer]); a
     * replacement while the notification is still showing updates it
     * without alerting again.
     */
    fun showImportConfirmation(
        context: Context,
        pendingCount: Int,
        stagedBeforeUnix: Long = System.currentTimeMillis() / 1000L,
    ) {
        if (pendingCount <= 0) return
        createNotificationChannel(context)

        fun actionIntent(action: String) = Intent(context, ImportConfirmationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_STAGED_BEFORE_UNIX, stagedBeforeUnix)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val importPending = PendingIntent.getBroadcast(context, 101, actionIntent(ACTION_IMPORT_NOW), flags)
        val laterPending = PendingIntent.getBroadcast(context, 102, actionIntent(ACTION_LATER), flags)
        val skipPending = PendingIntent.getBroadcast(context, 103, actionIntent(ACTION_SKIP), flags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("New Photos Detected")
            .setContentText("$pendingCount new photo(s) ready to import to branchDAM")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_upload, "Import now", importPending)
            .addAction(android.R.drawable.ic_menu_recent_history, "Later", laterPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skipPending)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * The silent, ongoing notification [ImportConfirmationWorker] runs
     * under when WorkManager executes it as a foreground service.
     */
    fun buildImportingNotification(context: Context): android.app.Notification {
        createNotificationChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Importing photos")
            .setContentText("Queuing photos for upload to branchDAM")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Applies a notification action to the pending items staged at or
     * before [stagedBeforeUnix]. Blocking engine calls and, for the
     * import actions, a copy of each item's original bytes: it runs in
     * [ImportConfirmationWorker], never in the broadcast receiver.
     *
     * - Import now / Later: queue each item for upload and forget its
     *   pending row once queued (a row whose enqueue failed stays
     *   pending). Import now syncs at once; Later leaves it to the
     *   periodic sync.
     * - Skip: suppress each item so no later scan offers it again.
     *
     * Returns false, leaving the notification in place, when the pending
     * items can't be read (the engine isn't open yet).
     */
    /**
     * Records the local IDs the confirmation notification is about to
     * announce (see BranchDamKeys.IMPORT_ANNOUNCED_IDS). Call it before
     * [showImportConfirmation].
     */
    fun recordAnnounced(context: Context, localIds: Collection<String>) {
        context.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(BranchDamKeys.IMPORT_ANNOUNCED_IDS, localIds.toSet())
            .apply()
    }

    internal fun announcedIds(context: Context?): Set<String>? =
        context?.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
            ?.getStringSet(BranchDamKeys.IMPORT_ANNOUNCED_IDS, null)

    fun handleAction(
        context: Context?,
        action: String?,
        stagedBeforeUnix: Long = Long.MAX_VALUE,
    ): Boolean {
        val pending = EngineHolder.listPendingImports() ?: return false
        // The rows the notification announced, when they were recorded;
        // the stagedBefore cutoff alone is second-resolution.
        val announced = announcedIds(context)
        val targets = pending.filter {
            it.createdAtUnix <= stagedBeforeUnix && (announced == null || it.localId in announced)
        }

        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)

        when (action) {
            ACTION_IMPORT_NOW -> {
                if (importItems(context, targets) > 0 && context != null) {
                    SyncScheduler.triggerImmediateSync(context)
                }
            }
            ACTION_LATER -> {
                importItems(context, targets)
                if (context != null) {
                    SyncScheduler.schedulePeriodicSync(context)
                }
            }
            ACTION_SKIP -> {
                for (item in targets) {
                    suppressItem(item.localId)
                }
            }
            else -> {
                // ACTIONS gates both callers; an action added there without
                // a branch here must not report the tap as handled.
                Log.w("ImportConfirmation", "no handler for import confirmation action $action")
                return false
            }
        }

        // A refresh that ran while the action was being applied (the
        // observer's startup announcement when this tap cold-started the
        // process, say) may have re-posted the notification for rows the
        // action has since cleared. Withdraw it when nothing is left.
        if (notificationManager != null && EngineHolder.listPendingImports()?.isEmpty() == true) {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        return true
    }

    /**
     * Queues [items] for upload; returns how many were queued. With a
     * Context each item goes through [UploadStaging], which reads its
     * original (unredacted) bytes on API 29+, as auto-import does.
     * Without one (unit tests) the recorded DATA path is enqueued.
     */
    private fun importItems(context: Context?, items: List<PendingImport>): Int {
        var queued = 0
        for (item in items) {
            val queueId = if (context != null) {
                UploadStaging.enqueue(context, item.toMediaItem())
            } else {
                EngineHolder.enqueueMedia(
                    localPath = item.localPath,
                    filename = item.filename,
                    capturedAtUnix = item.capturedAtUnix,
                    localId = item.localId,
                )
            }
            if (queueId > 0L) {
                EngineHolder.deletePendingImport(item.localId)
                queued++
            }
        }
        return queued
    }
}

/**
 * Rebuilds the [MediaItem] [UploadStaging] needs from a persisted pending
 * import. The MediaStore row ID is the content URI's last segment (it
 * names the staged copy); the other fields were recorded at staging.
 */
internal fun PendingImport.toMediaItem(): MediaItem = MediaItem(
    id = localId.substringAfterLast('/').toLongOrNull() ?: 0L,
    contentUri = localId,
    filePath = localPath,
    displayName = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    dateTakenUnix = capturedAtUnix,
    isRaw = filename.endsWith(".dng", ignoreCase = true) || mimeType == "image/x-adobe-dng",
)
