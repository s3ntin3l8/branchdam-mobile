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
 * Trailing-edge debouncing: each new [trigger] cancels any pending
 * [onFire] and schedules a new one [windowMs] in the future. A burst
 * of 50 events over 1s therefore produces exactly one [onFire] call
 * 500ms after the last trigger. Two well-separated bursts produce
 * one [onFire] each. The CONFLATED channel ensures [trigger] never
 * blocks — further triggers during the quiet window silently replace
 * the pending one.
 *
 * [onFire] runs on the [scope] provided by the caller; for
 * [MediaStoreObserver] that scope uses
 * [kotlinx.coroutines.Dispatchers.IO] so ContentProvider queries do
 * not block the HandlerThread or main.
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val onFire: suspend () -> Unit,
) {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var fireJob: Job? = null
            for (signal in channel) {
                fireJob?.cancel()
                fireJob = scope.launch {
                    delay(windowMs)
                    onFire()
                }
            }
        }
    }

    /**
     * Schedules [onFire] to run after a [windowMs] quiet period.
     * Non-blocking; safe to call from any thread including the main
     * thread. Coalesces with any pending trigger.
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
