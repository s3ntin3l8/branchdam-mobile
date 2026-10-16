package com.branchdam.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.otg.OtgIngestManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [OtgIngestManager] built without a Context (as the JVM tests build it)
 * reports its per-file and folder errors with English fallbacks instead
 * of the R.string.otg_error_* resources. Each fallback must be exactly
 * its resource's text, so a reworded resource cannot leave the fallback
 * (and the tests that assert it) saying something else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtgIngestManagerFallbackTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun noMediaInFolderFallbackMatchesItsResource() {
        assertEquals(
            context().getString(R.string.otg_error_no_media_in_folder),
            OtgIngestManager.NO_MEDIA_IN_FOLDER,
        )
    }

    @Test
    fun enqueueFailedFallbackMatchesItsResource() {
        assertEquals(context().getString(R.string.otg_error_enqueue_failed), OtgIngestManager.ENQUEUE_FAILED)
    }

    @Test
    fun unsafePathFallbackMatchesItsResource() {
        // The unformatted text, placeholder included, and the formatted one.
        assertEquals(context().getString(R.string.otg_error_unsafe_path), OtgIngestManager.UNSAFE_PATH)
        val path = "../DCIM/IMG_0001.JPG"
        assertEquals(
            context().getString(R.string.otg_error_unsafe_path, path),
            String.format(Locale.ROOT, OtgIngestManager.UNSAFE_PATH, path),
        )
    }

    @Test
    fun finishCopyFailedFallbackMatchesItsResource() {
        assertEquals(
            context().getString(R.string.otg_error_finish_copy_failed),
            OtgIngestManager.FINISH_COPY_FAILED,
        )
    }
}
