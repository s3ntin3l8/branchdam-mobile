package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.service.ImportConfirmationNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ImportConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val stagedBefore = intent.getLongExtra(
            ImportConfirmationNotifier.EXTRA_STAGED_BEFORE_UNIX,
            Long.MAX_VALUE,
        )
        val appContext = context.applicationContext

        // The action reads pending_import and enqueues uploads: blocking
        // engine calls that must not run on the main thread. When the tap
        // cold-starts the process, the engine is still opening on its init
        // thread, so wait for it before reading the pending items.
        val result = goAsync()
        scope.launch {
            try {
                EngineHolder.awaitInitialized(ENGINE_WAIT_MS)
                ImportConfirmationNotifier.handleAction(appContext, action, stagedBefore)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Stays well inside the broadcast's goAsync() window. */
        const val ENGINE_WAIT_MS = 8_000L
    }
}
