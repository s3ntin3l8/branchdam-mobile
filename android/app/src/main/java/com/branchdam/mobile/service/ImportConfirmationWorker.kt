package com.branchdam.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.branchdam.mobile.EngineHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies an import-confirmation notification action ("Import now",
 * "Later", "Skip") via [ImportConfirmationNotifier.handleAction].
 *
 * The work used to run inside the broadcast receiver's goAsync() window
 * (about 10 s): an engine wait on a cold start plus, for the import
 * actions, a copy of every pending item's original bytes off MediaStore,
 * with no cap on the number of items. The receiver now only enqueues this
 * worker; WorkManager gives it minutes and runs it expedited where quota
 * allows, so the tap still takes effect promptly.
 */
class ImportConfirmationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val action = inputData.getString(KEY_ACTION)
        if (action == null || action !in ImportConfirmationNotifier.ACTIONS) {
            Log.w(TAG, "ignoring import confirmation work without a known action: $action")
            return@withContext Result.failure()
        }
        val stagedBefore = inputData.getLong(KEY_STAGED_BEFORE_UNIX, Long.MAX_VALUE)

        val handled = try {
            // A tap that cold-starts the process finds the engine still
            // opening on its init thread: wait for it before reading
            // pending_import.
            EngineHolder.awaitInitialized(ENGINE_WAIT_MS)
            ImportConfirmationNotifier.handleAction(applicationContext, action, stagedBefore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLogger.log(applicationContext, "Import confirmation ($action) failed: ${e.message}", e)
            false
        }

        when (decide(handled, runAttemptCount)) {
            Decision.DONE -> Result.success()
            Decision.RETRY -> {
                SyncLogger.log(
                    applicationContext,
                    "Import confirmation ($action) not applied: engine not open after ${ENGINE_WAIT_MS}ms; retrying",
                )
                Log.w(TAG, "pending imports unreadable for $action; retrying")
                Result.retry()
            }
            Decision.GIVE_UP -> {
                SyncLogger.log(
                    applicationContext,
                    "Import confirmation ($action) not applied after ${runAttemptCount + 1} attempts; " +
                        "the items stay pending and are offered again",
                )
                Log.w(TAG, "pending imports unreadable for $action; giving up")
                Result.failure()
            }
        }
    }

    /**
     * Required for expedited work below API 31, where WorkManager runs it
     * as a foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            ImportConfirmationNotifier.IMPORTING_NOTIFICATION_ID,
            ImportConfirmationNotifier.buildImportingNotification(applicationContext),
            SyncNotificationHelper.foregroundServiceType(),
        )

    /** What a finished [doWork] attempt means for the work. */
    enum class Decision { DONE, RETRY, GIVE_UP }

    companion object {
        private const val TAG = "ImportConfirmWorker"

        const val UNIQUE_WORK_NAME = "branchdam_import_confirmation"
        const val KEY_ACTION = "action"
        const val KEY_STAGED_BEFORE_UNIX = "staged_before_unix"

        /**
         * How long one attempt waits for the engine to open on a cold
         * start; far longer than the receiver could afford.
         */
        const val ENGINE_WAIT_MS = 30_000L

        /** Retries after an attempt that could not read the pending items. */
        const val MAX_RETRIES = 1

        /**
         * An applied action is done. One that could not read the pending
         * items (the engine did not open in time) is retried once, then
         * given up: the items stay in pending_import and are announced
         * again, so nothing is lost.
         */
        internal fun decide(handled: Boolean, runAttemptCount: Int): Decision = when {
            handled -> Decision.DONE
            runAttemptCount < MAX_RETRIES -> Decision.RETRY
            else -> Decision.GIVE_UP
        }

        internal fun inputFor(action: String, stagedBeforeUnix: Long): Data =
            workDataOf(
                KEY_ACTION to action,
                KEY_STAGED_BEFORE_UNIX to stagedBeforeUnix,
            )

        internal fun buildRequest(action: String, stagedBeforeUnix: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ImportConfirmationWorker>()
                .setInputData(inputFor(action, stagedBeforeUnix))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        /**
         * Enqueues [action] for the items staged at or before
         * [stagedBeforeUnix]. All taps share one unique work chain and
         * APPEND_OR_REPLACE runs them one after another, in tap order:
         * two actions never read the same pending rows concurrently (which
         * would stage an item's bytes twice), no tap is dropped (KEEP would
         * lose a "Skip" sent while an "Import now" runs), and a repeated tap
         * is a cheap no-op because the first one already cleared the rows.
         * A failed earlier attempt does not block the chain.
         */
        fun enqueue(context: Context, action: String, stagedBeforeUnix: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(action, stagedBeforeUnix),
            )
        }
    }
}
