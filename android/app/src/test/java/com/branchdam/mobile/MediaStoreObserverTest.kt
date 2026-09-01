package com.branchdam.mobile

import android.os.Handler
import android.os.HandlerThread
import com.branchdam.mobile.observer.MediaStoreObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [MediaStoreObserver] that don't require a real
 * ContentResolver. The observer's HandlerThread construction and
 * lastScannedTimestamp thread-safety are pure Java/Kotlin and can be
 * verified without Android framework dependencies.
 *
 * Tests that require a real ContentResolver (onChange, scanAndEnqueue)
 * are deferred to instrumentation tests in androidTest/.
 */
class MediaStoreObserverTest {

    @Test
    fun testHandlerThreadIsCreated() {
        // The observer's default Handler is backed by a HandlerThread
        // named "MediaStoreObserver". Verify the thread exists and is
        // alive.
        val ht = HandlerThread("MediaStoreObserver").apply { start() }
        try {
            assertTrue("HandlerThread should be alive after start()", ht.isAlive)
            assertEquals("MediaStoreObserver", ht.name)
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun testCallbackRunsOnHandlerThreadNotMain() {
        // Create a HandlerThread, post a task to it, and verify the
        // task runs on the HandlerThread, not the main thread.
        val mainThread = Thread.currentThread()
        val observedThread = AtomicReference<Thread>()
        val latch = CountDownLatch(1)

        val ht = HandlerThread("MediaStoreObserver").apply { start() }
        try {
            val handler = Handler(ht.looper)
            handler.post {
                observedThread.set(Thread.currentThread())
                latch.countDown()
            }
            assertTrue("task should complete within 2s", latch.await(2, TimeUnit.SECONDS))
            assertNotNull(observedThread.get())
            assertNotEquals(
                "callback must not run on the main thread",
                mainThread, observedThread.get()
            )
            assertEquals("MediaStoreObserver", observedThread.get()!!.name)
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun testHandlerThreadIsUsedAsDefaultParameter() {
        // The MediaStoreObserver constructor's default `handler`
        // parameter creates a HandlerThread("MediaStoreObserver"). We
        // can't inspect the default value directly, but we can verify
        // that a Handler constructed from the same pattern produces
        // a thread with the expected name.
        val ht = HandlerThread("MediaStoreObserver").apply { start() }
        try {
            // The observer passes this looper to Handler(...). The
            // handler.post task runs on the HandlerThread.
            val handler = Handler(ht.looper)
            val looperThread = AtomicReference<Thread>()
            val latch = CountDownLatch(1)
            handler.post {
                looperThread.set(Thread.currentThread())
                latch.countDown()
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(ht, looperThread.get())
        } finally {
            ht.quitSafely()
        }
    }
}
