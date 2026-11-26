package com.branchdam.mobile.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.lineage.EditCorrelator
import com.branchdam.mobile.lineage.PairDetector
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.PendingImportAnnouncer
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.triage.ProcessedTrashStore
import com.branchdam.mobile.triage.TrashSyncObserver
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MediaStoreObserver(
    private val context: Context,
    handler: Handler = Handler(HandlerThread("MediaStoreObserver").apply { start() }.looper),
) : ContentObserver(handler) {

    private val prefs = context.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Initialized from [BranchDamKeys.OBSERVER_LAST_SCANNED_TIMESTAMP]
     * or defaults to 24 hours ago to catch any items missed while
     * the app was closed. T2-11: persisting this ensures we don't
     * miss media items across process restarts.
     */
    private val lastScannedTimestamp = AtomicLong(
        prefs.getLong(
            BranchDamKeys.OBSERVER_LAST_SCANNED_TIMESTAMP,
            System.currentTimeMillis() / 1000L - 86400 // 24h fallback
        )
    )

    /**
     * Content URIs of trashed items whose delete event was already
     * handled. Prevents re-enqueueing EVENT_NODE_DELETED for items
     * lingering in the Android 11+ recycle bin (up to 30 days) across
     * successive onChange callbacks and across process restarts: the
     * set is persisted in SharedPreferences (it used to live in memory,
     * so every app start re-sent a delete for everything in the bin).
     * Only touched from the single-in-flight debounced scan.
     */
    private val processedTrash = ProcessedTrashStore(prefs)

    /**
     * Bounded to [Dispatchers.IO] so the ContentProvider queries and
     * file I/O in [scanAndEnqueueNewMedia] do not block the
     * HandlerThread that [onChange] runs on, and never the main
     * thread. T2-3 lifts the blocking calls out of the observer
     * callback so the app stays responsive during burst photo
     * capture and iCloud sync.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("MediaStoreObserver")
    )

    /**
     * Reads the debounce window from SharedPreferences so the user can
     * tune it from the Advanced settings screen. Falls back to
     * [DEFAULT_DEBOUNCE_WINDOW_MS] if the key is absent.
     */
    private fun readDebounceWindowMs(): Long {
        val prefs = context.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(BranchDamKeys.OBSERVER_DEBOUNCE_MS, BranchDamKeys.DEFAULT_OBSERVER_DEBOUNCE_MS)
    }

    /**
     * Coalesces rapid-fire onChange callbacks into a single scan +
     * trash-sync + lineage pipeline run after a quiet window.
     * Burst capture that emits 50 events in 1 second collapses to one
     * scan after the window, satisfying the T2-3 acceptance criterion
     * of at most 2 scans per burst. Folding [scanAndSyncTrash] (which
     * runs the trash sync too) into the same debounced coroutine ensures
     * the burst-coalescing goal holds end-to-end: a 50-event burst
     * produces one recycle-bin query and one MediaStore rescan, not 50
     * of each.
     */
    private val debouncer = Debouncer(
        scope = scope,
        windowMs = ::readDebounceWindowMs,
        onFire = ::scanAndSyncTrash,
    )

    /**
     * Posts the import confirmation for the persisted pending_import set,
     * not just for what one scan pass staged: rows staged by a process
     * that died sit below the scan cursor and are never staged again.
     */
    private val importAnnouncer = PendingImportAnnouncer(
        post = { rows ->
            ImportConfirmationNotifier.recordAnnounced(context, rows.map { it.localId })
            ImportConfirmationNotifier.showImportConfirmation(context, rows.size, rows.maxOf { it.createdAtUnix })
        },
    )

    fun register() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
        announcePersistedPendingImports()
    }

    /**
     * At startup, re-announces the items an earlier process staged and
     * nobody answered. The engine opens on its own init thread, so wait
     * for it (on [Dispatchers.IO], never the caller's thread); if it is
     * not open in time the read fails, nothing is recorded as announced,
     * and the next scan's refresh announces instead.
     */
    private fun announcePersistedPendingImports() {
        scope.launch {
            try {
                EngineHolder.awaitInitialized(STARTUP_ENGINE_WAIT_MS)
                importAnnouncer.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "announcing persisted pending imports failed", e)
            }
        }
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        debouncer.close()
        scope.cancel()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        // Schedule a debounced scan + trash sync + lineage run.
        // Non-blocking: the [debouncer] coalesces bursts of onChange
        // into one scan after a quiet window so a 50-event burst
        // produces at most one ContentProvider rescan and one
        // recycle-bin query, not 50 of each. The trash sync was
        // previously run inline here and bypassed the debouncer, so
        // a 50-event burst still triggered 50 recycle-bin queries --
        // see Hermes PR-84 review thread on
        // MediaStoreObserver.kt:80.
        debouncer.trigger()
    }

    /**
     * Runs the new-media scan and the trash sync on [Dispatchers.IO].
     * Both are folded into a single debounced coroutine so a burst of
     * onChange callbacks collapses to one ContentProvider rescan plus
     * one recycle-bin query (Hermes PR-84 warning #2: only the main
     * scan was debounced). [scanAndEnqueueNewMedia] runs first so
     * newly-enqueued items get a fresh `lastScannedTimestamp` before
     * the trash sync reads it; the order is otherwise immaterial.
     */
    private suspend fun scanAndSyncTrash() {
        scanAndEnqueueNewMedia()

        // D.4: Trash sync -- MATCH_ONLY returns all items in the 30-day
        // recycle bin (images and videos). The persisted processedTrash
        // set prevents re-enqueueing delete events across onChange calls
        // and app restarts.
        val deletedCount = TrashSyncObserver.processTrashedItems(context, processedTrash)
        if (deletedCount > 0) {
            SyncScheduler.triggerImmediateSync(context)
        }
    }

    /**
     * Queries MediaStore for recent images and videos and records each
     * new item's intent -- an upload-queue row (auto-import) or a
     * pending_import row awaiting the user's answer -- BEFORE the scan
     * cursor moves past it (see [NewMediaIntake]). Items the user skipped
     * are not offered again. Then runs the lineage pipeline. Runs on
     * [Dispatchers.IO] because the caller's [scope] is bounded there.
     */
    @VisibleForTesting
    internal suspend fun scanAndEnqueueNewMedia() {
        val autoImport = ImportConfirmationNotifier.getAutoImportEnabled(context)
        val pendingIds = if (autoImport) {
            EngineHolder.listPendingImports()?.mapTo(mutableSetOf()) { it.localId } ?: return
        } else {
            emptySet()
        }
        val minTimestamp = lastScannedTimestamp.get()
        val images = MediaScanner.queryRecentImages(context, minDateTakenUnix = minTimestamp * 1000L)
        val videos = MediaScanner.queryRecentVideos(context, minDateTakenUnix = minTimestamp * 1000L)

        val result = NewMediaIntake.commit(
            startCursor = minTimestamp,
            scanned = images + videos,
            persist = { item ->
                // A rescan can see an already-offered item after auto-import is enabled.
                // Leave that item with the confirmation action, which may be in flight.
                if (item.contentUri in pendingIds) NewMediaIntake.Outcome.ALREADY_DECIDED
                else persistIntent(item, autoImport)
            },
            saveCursor = { cursor ->
                lastScannedTimestamp.set(cursor)
                prefs.edit().putLong(BranchDamKeys.OBSERVER_LAST_SCANNED_TIMESTAMP, cursor).apply()
            },
        )

        if (result.enqueued.isNotEmpty()) {
            // Pending items get their micro videos only when accepted.
            for (item in result.enqueued) {
                UploadStaging.enqueueMicroVideo(context, item, skipIfAlreadyQueued = true)
            }
            SyncScheduler.triggerImmediateSync(context)
        }
        // Announce everything still awaiting an answer -- the persisted
        // set, including items staged before a process death -- not just
        // what this pass staged.
        importAnnouncer.refresh(stagedThisPass = result.staged.size)

        // Record companion-pair and edit lineage for both auto-imported
        // items and those awaiting a confirmation action.
        val newItems = result.enqueued + result.staged
        if (newItems.isNotEmpty()) {
            runLineageDetection(newItems)
        }
    }

    /**
     * Records the intent for one scanned item. Auto-import queues it for
     * upload; if that fails (e.g. the file can't be read right now) the
     * item is staged as a pending import instead, so it is neither lost
     * nor allowed to stall the cursor. Without auto-import it is staged
     * for the confirmation notification.
     */
    private fun persistIntent(item: MediaItem, autoImport: Boolean): NewMediaIntake.Outcome {
        if (autoImport) {
            if (ImportConfirmationNotifier.isItemSuppressed(item.contentUri)) {
                return NewMediaIntake.Outcome.ALREADY_DECIDED
            }
            // Reads the original bytes where the platform allows it; a
            // location-redacted copy is never reclaimable (#303).
            val queueId = UploadStaging.enqueue(context, item)
            if (queueId > 0L) return NewMediaIntake.Outcome.ENQUEUED
        }
        return when (ImportConfirmationNotifier.stagePendingItem(item)) {
            EngineHolder.StageResult.STAGED -> NewMediaIntake.Outcome.STAGED
            EngineHolder.StageResult.SUPPRESSED -> NewMediaIntake.Outcome.ALREADY_DECIDED
            EngineHolder.StageResult.FAILED -> NewMediaIntake.Outcome.FAILED
        }
    }

    private fun runLineageDetection(newItems: List<MediaItem>) {
        // Query recent images to allow cross-batch companion pairing (e.g. RAW uploaded after companion JPEG).
        val recentImages = try {
            MediaScanner.queryRecentImages(context, minDateTakenUnix = 0, limit = 100)
        } catch (_: Throwable) {
            emptyList()
        }
        val candidateItems = (recentImages + newItems).distinctBy { it.id }

        // Pair detection (DNG + JPEG companion pairs).
        val pairs = PairDetector.findPairs(candidateItems)
        PairDetector.registerPairLineage(pairs)

        // Edit correlation (in-phone editor exports -> camera roll master).
        val editDerivatives = newItems.filter { item ->
            item.filePath.contains("Edited", ignoreCase = true) ||
                item.filePath.contains("Restored", ignoreCase = true) ||
                item.filePath.contains("Luminar", ignoreCase = true)
        }
        if (editDerivatives.isNotEmpty()) {
            val edits = EditCorrelator.findInPhoneEdits(newItems, editDerivatives)
            EditCorrelator.registerEditLineage(edits)
        }
    }

    private companion object {
        const val TAG = "MediaStoreObserver"

        /** How long the startup announcement waits for the engine to open. */
        const val STARTUP_ENGINE_WAIT_MS = 30_000L
    }
}
