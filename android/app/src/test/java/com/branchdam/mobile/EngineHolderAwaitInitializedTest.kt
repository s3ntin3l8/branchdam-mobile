package com.branchdam.mobile

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cold-process wait behind [EngineHolder.awaitInitialized]: a
 * component woken before the engine opened (a notification action, the
 * observer's startup announcement) blocks until the first initialize
 * attempt finishes, then sees whether it succeeded.
 */
class EngineHolderAwaitInitializedTest {

    private fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun testWithoutTheNativeLibraryItReturnsFalseAtOnce() {
        var ready = true
        val ms = elapsedMs { ready = EngineHolder.awaitInitialized(10_000L) }
        assertFalse(ready)
        assertTrue("must not wait for an engine that can never open (${ms}ms)", ms < 5_000L)
    }

    @Test
    fun testAlreadyInitializedReturnsWithoutWaiting() {
        val gate = FirstAttemptGate() // never released
        var ready = false
        val ms = elapsedMs { ready = gate.await(10_000L) { true } }
        assertTrue(ready)
        assertTrue("${ms}ms", ms < 5_000L)
    }

    @Test
    fun testWaitsForTheFirstAttemptAndReportsItsSuccess() {
        val gate = FirstAttemptGate()
        val initialized = AtomicBoolean(false)
        val init = Thread {
            Thread.sleep(100L)
            initialized.set(true) // EngineHolder sets the flag before releasing
            gate.release()
        }
        init.start()

        val ready = gate.await(10_000L) { initialized.get() }
        init.join()

        assertTrue("the waiter must see the attempt that released it", ready)
    }

    @Test
    fun testAFailedFirstAttemptReleasesWaitersWithFalse() {
        val gate = FirstAttemptGate()
        val init = Thread {
            Thread.sleep(100L)
            gate.release() // bindingOpen threw: released, not initialized
        }
        init.start()

        var ready = true
        val ms = elapsedMs { ready = gate.await(10_000L) { false } }
        init.join()

        assertFalse(ready)
        assertTrue("a failed attempt must not hold waiters until the timeout (${ms}ms)", ms < 5_000L)
    }

    @Test
    fun testTimesOutWhenTheFirstAttemptNeverFinishes() {
        val gate = FirstAttemptGate()
        var ready = true
        val ms = elapsedMs { ready = gate.await(50L) { false } }
        assertFalse(ready)
        assertTrue("must wait out the timeout (${ms}ms)", ms >= 45L)
    }

    @Test
    fun testLaterCallersDoNotWaitOnceReleased() {
        val gate = FirstAttemptGate()
        gate.release()
        gate.release() // idempotent
        var ready = true
        val ms = elapsedMs { ready = gate.await(10_000L) { false } }
        assertFalse(ready)
        assertTrue("${ms}ms", ms < 5_000L)
    }

    @Test
    fun testAnInterruptedWaitReturnsAndKeepsTheInterruptFlag() {
        val gate = FirstAttemptGate()
        Thread.currentThread().interrupt()
        val ready = gate.await(10_000L) { false }
        // Thread.interrupted() also clears the flag for the next test.
        assertTrue("the interrupt must be restored for the caller", Thread.interrupted())
        assertFalse(ready)
    }
}
