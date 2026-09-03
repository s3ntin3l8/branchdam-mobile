package com.branchdam.mobile.observer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Concurrency: the consumer-supplied [scope] is typically
 * `Dispatchers.IO + SupervisorJob` (a multi-threaded dispatcher),
 * which means the dispatcher loop and the inner [delay]-then-[onFire]
 * launch can run on different worker threads. All reads and writes
 * of the [fireInProgress] and [pending] flags are therefore guarded
 * by [mutex] so a stale read of [fireInProgress] cannot spawn a
 * parallel [onFire]. `@Volatile`-only would fix visibility of each
 * individual flag but not the compound check-then-act (`if
 * (fireInProgress) { pending = true }`) where the inner launch's
 * `finally` could clear [pending] before the dispatcher's write of
 * `true` becomes visible, dropping the trigger. The mutex
 * establishes a happens-before edge between the dispatcher's
 * "fireInProgress seen as true → pending = true" and the inner
 * launch's "fireInProgress = false → read pending → possibly
 * trySend", so no trigger can be lost.
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
    private val mutex = Mutex()
    private var delayJob: Job? = null
    private var fireInProgress = false
    private var pending = false

    init {
        scope.launch {
            for (signal in channel) {
                mutex.withLock {
                    if (fireInProgress) {
                        // A scan is currently running on a different IO
                        // worker thread and cannot be aborted mid-execution.
                        // Remember we still need to re-fire once the current
                        // scan returns so the events that arrived during the
                        // scan are not lost. The CONFLATED channel coalesces
                        // any further signals that arrive during the run into
                        // this one pending marker.
                        pending = true
                    } else {
                        // Trailing-edge debounce: cancel any pending delay
                        // and schedule a fresh one windowMs in the future.
                        delayJob?.cancel()
                        delayJob = scope.launch {
                            delay(windowMs)
                            // Acquiring the mutex here serialises the
                            // "fireInProgress = true" write with the
                            // dispatcher's read of fireInProgress below, so
                            // a trigger that races us cannot read a stale
                            // `false` and spawn a parallel scan.
                            mutex.withLock { fireInProgress = true }
                            try {
                                onFire()
                            } finally {
                                mutex.withLock {
                                    fireInProgress = false
                                    if (pending) {
                                        // Triggers arrived during onFire.
                                        // Re-debounce from the moment the
                                        // scan returned so the new events
                                        // are captured by a follow-up scan.
                                        pending = false
                                        channel.trySend(Unit)
                                    }
                                }
                            }
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
