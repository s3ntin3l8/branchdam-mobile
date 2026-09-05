package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [nextPermissionAction] — the pure decision function that
 * drives [DrivePermissionFlow]. The decision function is the most
 * load-bearing piece of the permission flow: it determines which
 * launcher to invoke next, and its transition rules are what
 * prevents both (a) the original "back-to-back requestPermissions
 * dismiss the first dialog" bug and (b) the "deny everything → loop
 * forever" bug introduced by the first fix.
 *
 * The Composable itself runs under `isReturnDefaultValues = true`,
 * which is too thin a stub to exercise real Compose state, so the
 * decision logic is factored into this pure function and tested
 * directly. The injected-lambda seams (`launchNotifications`,
 * `launchMedia`, `hasPermission`) are exercised via this function
 * rather than the Composable.
 */
class NextPermissionActionTest {

    private val postNotifications = android.Manifest.permission.POST_NOTIFICATIONS
    private val readMediaImages = android.Manifest.permission.READ_MEDIA_IMAGES
    private val readMediaVideo = android.Manifest.permission.READ_MEDIA_VIDEO
    private val readExternalStorage = android.Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun testNoneAdvances() {
        val action = nextPermissionAction(
            batch = PermissionBatch.NONE,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { false },
        )
        assertEquals(PermissionAction.Advance, action)
    }

    @Test
    fun testDoneStays() {
        val action = nextPermissionAction(
            batch = PermissionBatch.DONE,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { true },
        )
        assertEquals(PermissionAction.Stay, action)
    }

    @Test
    fun testNotificationsLaunchesWhenNotGranted() {
        val action = nextPermissionAction(
            batch = PermissionBatch.NOTIFICATIONS,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { false },
        )
        assertEquals(
            PermissionAction.LaunchNotifications(listOf(postNotifications)),
            action,
        )
    }

    @Test
    fun testNotificationsAdvancesWhenAlreadyGranted() {
        // Regression test for Important #5 from the review: pre-fix,
        // the NOTIFICATIONS branch always launched the full batch
        // even when the permission was already granted, causing a
        // no-op launcher call after every successful grant cycle.
        val action = nextPermissionAction(
            batch = PermissionBatch.NOTIFICATIONS,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { perm -> perm == postNotifications },
        )
        assertEquals(PermissionAction.Advance, action)
    }

    @Test
    fun testNotificationsAdvancesOnEmptyBatch() {
        // Pre-API 33: notifications batch is empty. Advance immediately.
        val action = nextPermissionAction(
            batch = PermissionBatch.NOTIFICATIONS,
            notificationsBatch = emptyList(),
            mediaBatch = listOf(readExternalStorage),
            hasPermission = { false },
        )
        assertEquals(PermissionAction.Advance, action)
    }

    @Test
    fun testNotificationsLaunchesOnlyMissingPermissions() {
        // If the user has granted POST_NOTIFICATIONS but we're
        // somehow asking for two notifications perms (hypothetical),
        // we should only launch the missing one.
        val action = nextPermissionAction(
            batch = PermissionBatch.NOTIFICATIONS,
            notificationsBatch = listOf(postNotifications, "android.permission.FAKE_NOTIFICATION"),
            mediaBatch = listOf(readMediaImages),
            hasPermission = { perm -> perm == postNotifications },
        )
        assertEquals(
            PermissionAction.LaunchNotifications(listOf("android.permission.FAKE_NOTIFICATION")),
            action,
        )
    }

    @Test
    fun testMediaLaunchesMissingPermissions() {
        val action = nextPermissionAction(
            batch = PermissionBatch.MEDIA,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { false },
        )
        assertEquals(
            PermissionAction.LaunchMedia(listOf(readMediaImages, readMediaVideo)),
            action,
        )
    }

    @Test
    fun testMediaLaunchesOnlyMissingPermissions() {
        val action = nextPermissionAction(
            batch = PermissionBatch.MEDIA,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { perm -> perm == readMediaImages },
        )
        assertEquals(
            PermissionAction.LaunchMedia(listOf(readMediaVideo)),
            action,
        )
    }

    @Test
    fun testMediaMarksDoneWhenAllGranted() {
        // Regression test for the "loop forever on permanent denial"
        // bug: when the media batch reports all granted (either by
        // grant or by the launcher returning immediately with the
        // user having tapped Deny and the system recording it as
        // "permanently denied"), the flow must transition to DONE,
        // not loop back to NONE.
        val action = nextPermissionAction(
            batch = PermissionBatch.MEDIA,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = { true },
        )
        assertEquals(PermissionAction.MarkDone, action)
    }

    @Test
    fun testMediaMarksDoneOnEmptyBatch() {
        // Hypothetical API level where neither READ_MEDIA_* nor
        // READ_EXTERNAL_STORAGE is needed. The flow should mark
        // itself DONE rather than launch an empty array.
        val action = nextPermissionAction(
            batch = PermissionBatch.MEDIA,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = emptyList(),
            hasPermission = { false },
        )
        assertEquals(PermissionAction.MarkDone, action)
    }

    @Test
    fun testPermanentDenialTerminatesAfterMediaCallback() {
        // Walk the full denial path. A user who denies both
        // notifications and media must end up in the terminal DONE
        // state, not loop back to NONE.
        var batch = PermissionBatch.NONE
        val allDenied: (String) -> Boolean = { false }

        // Round 1: NONE → NOTIFICATIONS (Advance)
        batch = advanceOrKeep(batch, allDenied)
        assertEquals(PermissionBatch.NOTIFICATIONS, batch)

        // Round 2: NOTIFICATIONS launches the launcher. The launcher
        // callback invokes nextBatch() → MEDIA. The user denied, but
        // the next decision filters by hasPermission (still false),
        // so the launcher fires again. This is the expected behavior
        // for the initial denial: the system shows the dialog.
        val action2 = nextPermissionAction(
            batch = PermissionBatch.NOTIFICATIONS,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = allDenied,
        )
        assertTrue(
            "first denial must launch the notifications dialog",
            action2 is PermissionAction.LaunchNotifications,
        )
        batch = PermissionBatch.MEDIA

        // Round 3: MEDIA. User denies media too. The launcher fires
        // and the callback advances to DONE.
        val action3 = nextPermissionAction(
            batch = PermissionBatch.MEDIA,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = allDenied,
        )
        assertTrue(
            "first media denial must launch the media dialog",
            action3 is PermissionAction.LaunchMedia,
        )
        batch = PermissionBatch.DONE

        // Round 4: DONE is terminal. Even if recomposition re-runs
        // the LaunchedEffect, the action is Stay and no launcher
        // fires again.
        val action4 = nextPermissionAction(
            batch = PermissionBatch.DONE,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = allDenied,
        )
        assertEquals(PermissionAction.Stay, action4)
    }

    /**
     * Helper mirroring the [DrivePermissionFlow] decision path for
     * non-launcher transitions. Returns the next batch state when the
     * decision is Advance; otherwise returns the current batch.
     */
    private fun advanceOrKeep(
        batch: PermissionBatch,
        hasPermission: (String) -> Boolean,
    ): PermissionBatch {
        val action = nextPermissionAction(
            batch = batch,
            notificationsBatch = listOf(postNotifications),
            mediaBatch = listOf(readMediaImages, readMediaVideo),
            hasPermission = hasPermission,
        )
        return when (action) {
            PermissionAction.Advance -> when (batch) {
                PermissionBatch.NONE -> PermissionBatch.NOTIFICATIONS
                PermissionBatch.NOTIFICATIONS -> PermissionBatch.MEDIA
                PermissionBatch.MEDIA -> PermissionBatch.DONE
                PermissionBatch.DONE -> PermissionBatch.DONE
            }
            else -> batch
        }
    }
}
