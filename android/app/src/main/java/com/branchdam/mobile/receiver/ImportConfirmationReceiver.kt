package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.ImportConfirmationWorker

/**
 * Receives the import-confirmation notification's action taps and hands
 * each one to [ImportConfirmationWorker]. Applying an action means engine
 * calls (after waiting for the engine on a cold start) and, for the
 * import actions, copying every pending item's original bytes: far too
 * much for a receiver's goAsync() window, so it only enqueues and returns.
 */
class ImportConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ImportConfirmationNotifier.ACTIONS) return
        val stagedBefore = intent.getLongExtra(
            ImportConfirmationNotifier.EXTRA_STAGED_BEFORE_UNIX,
            Long.MAX_VALUE,
        )
        ImportConfirmationWorker.enqueue(context.applicationContext, action, stagedBefore)
    }
}
