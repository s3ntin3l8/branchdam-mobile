package com.branchdam.mobile

import android.content.Context
import com.branchdam.mobile.observer.MediaStoreObserver
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for MediaStoreObserver. With isReturnDefaultValues=true in
 * testOptions, the test JVM returns default values (null, 0, false)
 * for Android framework methods instead of throwing RuntimeException.
 * This lets us construct a MediaStoreObserver with a mock Context
 * and verify its structural contract.
 *
 * The observer's register() and unregister() call
 * context.contentResolver.registerContentObserver() which is not
 * testable in the JVM unit-test environment (it requires a real
 * ContentResolver backed by a ContentProvider). The full lifecycle
 * is covered by instrumentation tests in androidTest/.
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
}
