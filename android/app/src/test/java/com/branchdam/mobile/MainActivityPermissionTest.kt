package com.branchdam.mobile

import com.branchdam.mobile.runtimePermissionBatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [runtimePermissionBatches] — the pure function that
 * partitions the app's runtime permissions by API level. The
 * surrounding Compose state machine (PermissionFlowState) and
 * DrivePermissionFlow Composable are covered by the unit-test JVM
 * path with `isReturnDefaultValues = true`; the partitioning logic
 * itself is the contract that survives across API levels and
 * releases, so we lock it down here.
 *
 * The Build.VERSION.SDK_INT checks inside the production code read
 * the device's actual API level, so we cannot exercise both branches
 * in a single JVM. These tests pin the *current* contract for the
 * device the tests run on (API 35 in CI), and the pre-API33 branch is
 * exercised by reading the production code paths in code review.
 */
class MainActivityPermissionTest {

    @Test
    fun testRuntimePermissionBatchesSeparatesNotificationsAndMedia() {
        val (notifications, media) = runtimePermissionBatches()

        assertTrue(
            "notifications batch must never overlap media batch",
            notifications.intersect(media.toSet()).isEmpty(),
        )
        assertTrue(
            "at least one media permission is always required",
            media.isNotEmpty(),
        )
    }

    @Test
    fun testRuntimePermissionBatchesAtLeastOneMediaPermissionOnCurrentDevice() {
        val (_, media) = runtimePermissionBatches()

        // The exact list depends on Build.VERSION.SDK_INT. On API 33+
        // (the test environment) it is READ_MEDIA_IMAGES and
        // READ_MEDIA_VIDEO; on older devices it is READ_EXTERNAL_STORAGE.
        // The contract is: at least one permission, all named after
        // existing android.Manifest.permission constants.
        assertTrue(media.isNotEmpty())
        media.forEach { perm ->
            assertTrue(
                "permission '$perm' must start with android.permission.",
                perm.startsWith("android.permission."),
            )
        }
    }

    @Test
    fun testRuntimePermissionBatchesNotificationsEmptyPreTiramisu() {
        // This invariant is enforced by the if/else inside the
        // production function. We exercise it indirectly by asserting
        // that POST_NOTIFICATIONS only appears on Tiramisu+; the
        // production function reads Build.VERSION.SDK_INT at runtime.
        val (notifications, _) = runtimePermissionBatches()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            assertTrue(
                "pre-Tiramisu devices must not request POST_NOTIFICATIONS",
                notifications.isEmpty(),
            )
        } else {
            assertTrue(
                "Tiramisu+ devices must request POST_NOTIFICATIONS",
                notifications.contains(android.Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    @Test
    fun testRuntimePermissionBatchesMediaSelectionMatchesApiLevel() {
        val (_, media) = runtimePermissionBatches()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                setOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                ),
                media.toSet(),
            )
        } else {
            assertEquals(
                listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                media,
            )
        }
    }
}
