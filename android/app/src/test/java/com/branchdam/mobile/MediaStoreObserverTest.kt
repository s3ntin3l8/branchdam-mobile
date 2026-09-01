package com.branchdam.mobile

import android.content.Context
import com.branchdam.mobile.observer.MediaStoreObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for MediaStoreObserver. With isReturnDefaultValues=true in
 * testOptions, the test JVM returns default values (null, 0, false)
 * for Android framework methods instead of throwing RuntimeException.
 * This lets us construct a MediaStoreObserver with a mock Context
 * and verify its public surface.
 *
 * The observer's full lifecycle (register, onChange, scanAndEnqueue)
 * requires a real ContentResolver + MediaStore provider, which is
 * not available in unit tests. These tests verify the structural
 * contract: the observer is constructable, its public methods exist
 * with the correct signatures, and the singleton is stable.
 */
class MediaStoreObserverTest {

    @Test
    fun testObserverIsConstructable() {
        // The observer takes a Context (required for register). With
        // a mock Context, construction succeeds. The default Handler
        // parameter is a HandlerThread; with isReturnDefaultValues,
        // HandlerThread() returns a stub instead of throwing.
        val context: Context = mock()
        val observer = MediaStoreObserver(context)
        assertNotNull(observer)
    }

    @Test
    fun testObserverExtendsContentObserver() {
        val context: Context = mock()
        val observer = MediaStoreObserver(context)
        // ContentObserver is the Android base class for receiving
        // content-change notifications. MediaStoreObserver inherits
        // from it so the ContentResolver can dispatch onChange calls.
        assertNotNull(observer as android.database.ContentObserver)
    }

    @Test
    fun testStartStopObservingMethodsExist() {
        val context: Context = mock()
        val observer = MediaStoreObserver(context)
        // register() and unregister() are the public lifecycle hooks.
        // They should not throw when called on a mock context (with
        // isReturnDefaultValues, the underlying ContentResolver
        // calls return defaults).
        observer.register()
        observer.unregister()
    }

    @Test
    fun testRegisterIsIdempotent() {
        // Multiple register() calls should not crash. The observer
        // guards against double-registration internally.
        val context: Context = mock()
        val observer = MediaStoreObserver(context)
        observer.register()
        observer.register()
        observer.register()
        observer.unregister()
    }

    @Test
    fun testUnregisterWithoutRegisterDoesNotCrash() {
        // unregister() on a never-registered observer should not
        // throw. The observer guards against this internally.
        val context: Context = mock()
        val observer = MediaStoreObserver(context)
        observer.unregister()
    }
}
