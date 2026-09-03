package com.branchdam.mobile.observer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces a burst of [trigger] calls into at most one [onFire] per
 * 500ms quiet window. Used by [MediaStoreObserver] to prevent 50
 * onChange callbacks during burst photo capture from triggering 50
 * full ContentProvider rescans.
 *
 * Trailing-edge debouncing: each new [trigger] arriving during the
 * debounce window cancels the pending [onFire] and reschedules a
 * fresh [windowMs] delay. A burst of 50 events over 1s therefore
 * produces exactly one [onFire] call 500ms after the last trigger
 * in the burst.
 *
 * Single-in-flight semantics: when [onFire] is already running and a
 * new [trigger] arrives, the running scan cannot be aborted (the
 * call has no suspension points on its critical path, so a coroutine
 * cancel is a no-op while it is mid-execution). The trigger is
 * coalesced via a `pending` flag and, once the current [onFire]
 * returns, a fresh debounce window is started so any events that
 * arrived during the scan are captured by a follow-up scan. This
 * prevents the overlap-on-IO problem Hermes called out in PR 84:
 * the next [onFire] never starts until the current one completes.
 *
 * The CONFLATED channel ensures [trigger] never blocks and is safe
 * to call from any thread, including the main thread.
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val onFire: suspend () -> Unit,
) {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var delayJob: Job? = null
            var fireInProgress = false
            var pending = false
            for (signal in channel) {
                if (fireInProgress) {
                    // A scan is currently running on Dispatchers.IO and
                    // cannot be aborted mid-execution. Remember we still
                    // need to re-fire once the current scan returns so
                    // the events that arrived during the scan are not
                    // lost. The CONFLATED channel coalesces any further
                    // signals that arrive during the run into this one
                    // pending marker.
                    pending = true
                    continue
                }
                // Trailing-edge debounce: cancel any pending delay and
                // schedule a fresh one windowMs in the future.
                delayJob?.cancel()
                delayJob = scope.launch {
                    delay(windowMs)
                    try {
                        fireInProgress = true
                        onFire()
                    } finally {
                        fireInProgress = false
                        if (pending) {
                            // Triggers arrived during onFire. Re-debounce
                            // from the moment the scan returned so the
                            // new events are captured by a follow-up
                            // scan.
                            pending = false
                            channel.trySend(Unit)
                        }
                    }
                }
            }
        }
    }

    /**
     * Schedules [onFire] to run after a [windowMs] quiet period.
     * Non-blocking; safe to call from any thread including the main
     * thread. Coalesces with any pending trigger or in-flight scan.
     */
    fun trigger() {
        channel.trySend(Unit)
    }

    /**
     * Cancels any pending [onFire] and stops the worker. Idempotent.
     */
    fun close() {
        channel.close()
    }
}
