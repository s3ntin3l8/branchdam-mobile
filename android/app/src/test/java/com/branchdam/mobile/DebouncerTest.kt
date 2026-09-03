package com.branchdam.mobile

import com.branchdam.mobile.observer.Debouncer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Debouncer used by MediaStoreObserver to coalesce
 * bursts of onChange callbacks into at most a single scan per quiet
 * window. The test JVM uses kotlinx-coroutines-test's virtual clock,
 * so timing assertions are deterministic and do not depend on real
 * wall-clock time.
 *
 * The acceptance criterion from T2-3 is: a burst of 50 events in 1s
 * triggers at most 2 scans. The first scan covers the initial
 * debounce window; the second covers the post-burst quiet period.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun testBurstOf50EventsTriggersAtMost2Calls() = runTest {
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 500L,
            onFire = { callCount[0]++ }
        )

        // Burst of 50 events over 1 second (20ms apart).
        repeat(50) {
            debouncer.trigger()
            advanceTimeBy(20L)
        }

        // Allow the worker to consume pending signals and run the
        // first scan + drain.
        runCurrent()

        // Allow the post-burst quiet window to elapse and the
        // second scan to fire.
        advanceTimeBy(500L)
        runCurrent()

        // Drain any final waits.
        advanceUntilIdle()

        assertTrue(
            "Burst of 50 events must trigger at most 2 fires, got ${callCount[0]}",
            callCount[0] <= 2
        )

        debouncer.close()
    }

    @Test
    fun testSingleTriggerFiresAfterQuietWindow() = runTest {
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 500L,
            onFire = { callCount[0]++ }
        )

        debouncer.trigger()
        // No fires yet -- we just triggered and the quiet window
        // hasn't elapsed.
        runCurrent()
        assertEquals(0, callCount[0])

        // After the quiet window, exactly one fire.
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, callCount[0])

        // Advancing further without new triggers must not fire again.
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(1, callCount[0])

        debouncer.close()
    }

    @Test
    fun testEventsDuringQuietWindowCoalesce() = runTest {
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 500L,
            onFire = { callCount[0]++ }
        )

        // First trigger starts the quiet window.
        debouncer.trigger()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(0, callCount[0])

        // Trigger again at t=100 (within first quiet window).
        debouncer.trigger()
        advanceTimeBy(300L)
        // Now at t=400; first quiet window would end at t=500.
        // A new trigger at t=400 resets the window to t=900.
        debouncer.trigger()
        runCurrent()

        // At t=500, the original quiet window would have fired,
        // but the t=400 trigger means it must not.
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(0, callCount[0])

        // At t=900, the second quiet window fires once.
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(1, callCount[0])

        debouncer.close()
    }

    @Test
    fun testTwoSeparateBurstsFireTwice() = runTest {
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 500L,
            onFire = { callCount[0]++ }
        )

        // Burst 1: 5 events over 100ms.
        repeat(5) {
            debouncer.trigger()
            advanceTimeBy(20L)
        }
        // Wait for burst 1's scan.
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, callCount[0])

        // Idle period of 1s (no triggers).
        advanceTimeBy(1000L)
        runCurrent()

        // Burst 2: 5 events over 100ms.
        repeat(5) {
            debouncer.trigger()
            advanceTimeBy(20L)
        }
        // Wait for burst 2's scan.
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, callCount[0])

        debouncer.close()
    }
}
