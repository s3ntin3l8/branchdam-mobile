package com.branchdam.mobile.triage

import android.content.IntentSender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A system delete confirmation waiting for the UI to launch it. The UI
 * observes [MediaDeleteCoordinator.pendingConsent], launches
 * [intentSender] once (marking it [launched] so a recomposition does not
 * launch it twice) and reports RESULT_OK / anything else back.
 */
data class PendingDeleteConsent(
    val id: Long,
    val intentSender: IntentSender,
    val itemCount: Int,
    val launched: Boolean = false,
)

/**
 * Runs one [MediaStoreDeleter] operation at a time on behalf of a
 * ViewModel, turning its [DeleteStep.NeedsConsent] pauses into a
 * [pendingConsent] state the Composable launches, and resuming when the
 * Composable reports the user's answer.
 */
class MediaDeleteCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: () -> CoroutineDispatcher,
) {
    private class Active(
        val deleter: MediaStoreDeleter,
        val onDone: suspend (DeleteStep.Done) -> Unit,
        var awaiting: DeleteStep.NeedsConsent? = null,
    )

    private val lock = Any()
    private var active: Active? = null
    private var nextId = 0L

    private val _pendingConsent = MutableStateFlow<PendingDeleteConsent?>(null)
    val pendingConsent: StateFlow<PendingDeleteConsent?> = _pendingConsent.asStateFlow()

    val isBusy: Boolean get() = synchronized(lock) { active != null }

    /**
     * Starts deleting [uris]. Returns false (and never calls [onDone]) if
     * another delete is still waiting on the user.
     */
    fun delete(
        deleter: MediaStoreDeleter,
        uris: List<String>,
        onDone: suspend (DeleteStep.Done) -> Unit,
    ): Boolean {
        val op = Active(deleter, onDone)
        synchronized(lock) {
            if (active != null) return false
            active = op
        }
        scope.launch(dispatcher()) { advance(op, deleter.start(uris)) }
        return true
    }

    /**
     * Claims the pending request [id] for launching. Returns true exactly
     * once per request, so two screens observing the same ViewModel (the
     * gallery grid and its detail page) cannot both launch it.
     */
    fun onConsentLaunched(id: Long): Boolean = synchronized(lock) {
        val current = _pendingConsent.value
        if (current == null || current.id != id || current.launched) {
            false
        } else {
            _pendingConsent.value = current.copy(launched = true)
            true
        }
    }

    /** [approved] is true only for `Activity.RESULT_OK`. */
    fun onConsentResult(approved: Boolean) {
        val (op, step) = synchronized(lock) {
            val current = active ?: return
            val awaiting = current.awaiting ?: return
            current.awaiting = null
            current to awaiting
        }
        _pendingConsent.value = null
        scope.launch(dispatcher()) { advance(op, op.deleter.resume(step, approved)) }
    }

    private suspend fun advance(op: Active, step: DeleteStep) {
        when (step) {
            is DeleteStep.NeedsConsent -> {
                val id = synchronized(lock) {
                    op.awaiting = step
                    ++nextId
                }
                _pendingConsent.value = PendingDeleteConsent(
                    id = id,
                    intentSender = step.request.intentSender,
                    itemCount = step.request.uris.size,
                )
            }
            is DeleteStep.Done -> {
                synchronized(lock) {
                    if (active === op) active = null
                }
                _pendingConsent.value = null
                op.onDone(step)
            }
        }
    }
}
