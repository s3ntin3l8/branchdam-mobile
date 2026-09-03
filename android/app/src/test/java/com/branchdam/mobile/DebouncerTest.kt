package com.branchdam.mobile

import com.branchdam.mobile.observer.Debouncer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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

    /**
     * Hermes PR-84 warning #1: cancel-and-relaunch cannot abort a
     * running scan (the scan has no suspension points past its
     * initial delay), so the next trigger would spawn a second scan
     * that overlaps the first on Dispatchers.IO. The fix is
     * single-in-flight: a trigger arriving while onFire is running
     * is coalesced via a `pending` flag; once the current onFire
     * returns, a fresh debounce window starts so a follow-up scan
     * captures the events that arrived during the run. This test
     * pins that invariant: at no point are two onFire coroutines
     * active concurrently, and a trigger arriving during onFire
     * results in exactly one additional fire once the current one
     * completes.
     *
     * Timing (all virtual):
     *   t=0          trigger #1
     *   t=500        first scan begins (delay 500ms)
     *   t=500..700   first scan runs (delay 200ms inside onFire)
     *   t=510..600   10 triggers during the scan -- pending set, no parallel scan
     *   t=700        first scan done, follow-up scheduled at t=1200
     *   t=1200       follow-up scan begins
     *   t=1400       follow-up scan done
     */
    @Test
    fun testTriggerDuringOnFireDoesNotStartParallelRun() = runTest {
        val activeRuns = intArrayOf(0)
        val maxActive = intArrayOf(0)
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 500L,
            onFire = {
                activeRuns[0]++
                if (activeRuns[0] > maxActive[0]) maxActive[0] = activeRuns[0]
                callCount[0]++
                try {
                    // Simulate a long-running scan (e.g. MediaStore
                    // query + engine enqueue on Dispatchers.IO) so
                    // that triggers arriving during this window have
                    // to be coalesced rather than cancelled.
                    delay(200L)
                } finally {
                    activeRuns[0]--
                }
            }
        )

        // First trigger at t=0; onFire will run from t=500 to t=700.
        debouncer.trigger()
        advanceTimeBy(500L)  // t=500
        runCurrent()
        assertEquals("first scan in flight", 1, activeRuns[0])
        assertEquals(1, callCount[0])

        // Burst of 10 triggers while the first scan is running
        // (t=500 to t=700). These must NOT spawn a parallel scan.
        repeat(10) {
            debouncer.trigger()
            advanceTimeBy(10L)
        }
        assertEquals(
            "no parallel scan may start while onFire is running",
            1,
            maxActive[0]
        )
        assertEquals("still exactly one scan in flight", 1, activeRuns[0])
        assertEquals("still exactly one fire so far", 1, callCount[0])

        // Allow the first scan to complete (t=700) and the
        // pending-trigger re-fire to schedule (delay 500ms -> fires
        // at t=1200).
        advanceTimeBy(100L)  // t=700
        runCurrent()
        assertEquals("first scan done, follow-up scheduled", 0, activeRuns[0])
        assertEquals("follow-up has not yet fired", 1, callCount[0])

        // Advance into the follow-up window: the re-fire should run
        // a single scan.
        advanceTimeBy(500L)  // t=1200, follow-up fires
        runCurrent()
        assertEquals("follow-up scan in flight", 1, activeRuns[0])
        assertEquals("follow-up fired exactly once", 2, callCount[0])

        // Wait for the follow-up scan to finish and confirm no
        // further re-fires happen (no triggers after the burst).
        advanceTimeBy(200L)  // t=1400
        runCurrent()
        assertEquals("follow-up scan done", 0, activeRuns[0])
        assertEquals("no extra fires after the burst", 2, callCount[0])

        // The original Hermes concern: a second scan running
        // concurrently with the first. The max-active counter
        // captures that, and must stay at 1 throughout.
        assertEquals(
            "Hermes single-in-flight invariant: max one concurrent scan",
            1,
            maxActive[0]
        )

        debouncer.close()
    }

    /**
     * Companion to the single-in-flight test: when a trigger arrives
     * during the follow-up debounce window (i.e. after onFire has
     * returned and the follow-up is waiting), the follow-up must be
     * cancelled and rescheduled to fire windowMs after the new
     * trigger -- NOT at the original scheduled time. This pins the
     * trailing-edge debounce semantics on the post-onFire re-fire:
     * a fresh debounce window starts from the moment of re-trigger,
     * so further events that arrive during the follow-up window can
     * coalesce to it.
     *
     * Timing (all virtual):
     *   t=0          trigger #1
     *   t=100        first scan begins
     *   t=100..300   first scan runs (delay 200ms inside onFire)
     *   t=300        first scan done, follow-up scheduled at t=400
     *   t=350        trigger #2 mid-window -> cancels follow-up, reschedules to t=450
     *   t=400        original follow-up time -- must NOT fire
     *   t=450        rescheduled follow-up fires
     */
    @Test
    fun testReFireAfterOnFireRespectsDebounceWindow() = runTest {
        val callCount = intArrayOf(0)
        val debouncer = Debouncer(
            scope = this,
            windowMs = 100L,
            onFire = {
                callCount[0]++
                delay(200L)
            }
        )

        debouncer.trigger()
        advanceTimeBy(100L)  // t=100, first scan starts
        runCurrent()
        assertEquals(1, callCount[0])

        // Trigger mid-scan (t=100 to t=300); marks pending. The
        // first scan completes at t=300 and schedules a follow-up
        // fire at t=400.
        debouncer.trigger()
        advanceTimeBy(200L)  // t=300, first scan done, follow-up scheduled at t=400
        runCurrent()
        assertEquals("follow-up scheduled but not yet fired", 1, callCount[0])

        // Advance into the follow-up window (between t=300 and
        // t=400) and trigger. This cancels the follow-up scheduled
        // for t=400 and reschedules it to fire 100ms after this
        // trigger, i.e. at t=450.
        advanceTimeBy(50L)  // t=350
        runCurrent()
        debouncer.trigger()  // trigger at t=350 -> reschedules follow-up to t=450
        runCurrent()
        assertEquals(
            "rescheduled follow-up must not have fired yet",
            1,
            callCount[0]
        )

        // The original follow-up time (t=400) must NOT fire -- the
        // new trigger pushed it out.
        advanceTimeBy(50L)  // t=400
        runCurrent()
        assertEquals(
            "trigger reset the window, original follow-up time is no longer a fire",
            1,
            callCount[0]
        )

        // The new follow-up time (t=450) fires.
        advanceTimeBy(50L)  // t=450
        runCurrent()
        assertEquals("follow-up fires at the new rescheduled time", 2, callCount[0])

        debouncer.close()
    }

    /**
     * Hermes PR-84 round-2 warning: the single-in-flight invariant
     * was relying on captured `var` flags that are written by the
     * inner launch on one IO worker thread and read by the
     * dispatcher loop on another, with no synchronization. A stale
     * read of `fireInProgress=false` would let a trigger arriving
     * while onFire is still running spawn a parallel onFire on
     * Dispatchers.IO.
     *
     * The unit tests above all run on a single-threaded virtual-clock
     * `TestScope`, so they never exercise this cross-thread path --
     * Hermes called this out explicitly. This test does, by driving
     * the Debouncer from a real `Dispatchers.IO` scope and firing
     * `trigger()` from many threads at once, then asserting that
     * `onFire` is never entered concurrently and that the number of
     * fires matches the trailing-edge-debounce expectation.
     *
     * The Debouncer is fixed with a `Mutex` that guards every read
     * and write of `fireInProgress` / `pending`, so the dispatcher's
     * "fireInProgress seen as true -> pending = true" is ordered
     * before the inner launch's "fireInProgress = false -> read
     * pending -> possibly trySend". Without the mutex, `maxActive`
     * regularly reports 2 or 3 on this workload.
     */
    @Test(timeout = 30000)
    fun testNoParallelScansFromMultipleTriggerThreads() = runBlocking {
        val activeRuns = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val callCount = AtomicInteger(0)
        val triggerCount = 200
        val triggerThreads = 8
        val windowMs = 50L

        val debouncerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val debouncer = Debouncer(
            scope = debouncerScope,
            windowMs = windowMs,
            onFire = {
                val now = activeRuns.incrementAndGet()
                // Atomic CAS loop to track the high-water mark across
                // concurrent onFire entries without a synchronized block.
                var observed = maxActive.get()
                while (now > observed && !maxActive.compareAndSet(observed, now)) {
                    observed = maxActive.get()
                }
                try {
                    callCount.incrementAndGet()
                    // Slow scan -- intentionally longer than windowMs so a
                    // new trigger arriving while we are inside onFire has a
                    // chance to observe a stale `fireInProgress=false` if
                    // the flag is unsynchronised.
                    delay(150L)
                } finally {
                    activeRuns.decrementAndGet()
                }
            }
        )

        try {
            val startGate = CountDownLatch(1)
            val readyLatch = CountDownLatch(triggerThreads)
            val doneLatch = CountDownLatch(triggerThreads)

            // Each thread fires `triggerCount / triggerThreads` triggers,
            // waiting on startGate so all threads release triggers as
            // close to simultaneously as possible -- maximising the
            // chance that a trigger arrives while onFire is mid-flight.
            val threads = (1..triggerThreads).map { _ ->
                Thread {
                    readyLatch.countDown()
                    startGate.await()
                    val perThread = triggerCount / triggerThreads
                    repeat(perThread) {
                        debouncer.trigger()
                        // Tiny pause so trySend/launch interleaving is
                        // realistic rather than a tight spin loop.
                        Thread.sleep(0, 500_000)
                    }
                    doneLatch.countDown()
                }.apply { start() }
            }

            // Wait for every thread to be at the gate, then release.
            assertTrue(
                "trigger threads did not all reach the start gate",
                readyLatch.await(10, TimeUnit.SECONDS)
            )
            startGate.countDown()

            // Wait for every thread to finish firing.
            assertTrue(
                "trigger threads did not finish in time",
                doneLatch.await(10, TimeUnit.SECONDS)
            )

            // Give the debouncer time to drain the trailing debounce
            // window plus one more 150ms scan so the final fire
            // completes.
            delay((windowMs + 200L) * 5)

            // The Hermes invariant: never more than one onFire is
            // running -- regardless of how many threads are firing
            // triggers concurrently.
            assertEquals(
                "Hermes single-in-flight invariant: max one concurrent scan across threads",
                1,
                maxActive.get()
            )

            // Sanity: at least one fire happened, and we did not see
            // an unbounded number (the trailing-edge debounce should
            // collapse a 200-trigger storm to well under 200 fires).
            assertTrue(
                "expected at least one onFire, got ${callCount.get()}",
                callCount.get() >= 1
            )
            assertTrue(
                "trailing-edge debounce should hold fires well below trigger count (got ${callCount.get()} for $triggerCount triggers)",
                callCount.get() < triggerCount
            )

            // All scans finished.
            assertEquals(0, activeRuns.get())
        } finally {
            debouncer.close()
            debouncerScope.cancel()
        }
    }
}
