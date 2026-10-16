package com.branchdam.mobile

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the API-level media permission batches, the partial-access
 * ("Select photos", API 34+) classification, and how the permission flow
 * treats a partial grant and ACCESS_MEDIA_LOCATION.
 */
class MediaAccessPermissionTest {

    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val userSelected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val location = Manifest.permission.ACCESS_MEDIA_LOCATION
    private val external = Manifest.permission.READ_EXTERNAL_STORAGE
    private val notifications = Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun batchesPerApiLevel() {
        assertEquals(emptyList<String>() to listOf(external), runtimePermissionBatches(28))
        assertEquals(emptyList<String>() to listOf(external, location), runtimePermissionBatches(29))
        assertEquals(emptyList<String>() to listOf(external, location), runtimePermissionBatches(32))
        assertEquals(listOf(notifications) to listOf(images, video, location), runtimePermissionBatches(33))
        assertEquals(
            listOf(notifications) to listOf(images, video, userSelected, location),
            runtimePermissionBatches(34),
        )
    }

    @Test
    fun accessLevelFollowsThePlatformGuidance() {
        val none: (String) -> Boolean = { false }
        assertEquals(MediaAccessLevel.DENIED, mediaAccessLevel(34, none))
        assertEquals(MediaAccessLevel.FULL, mediaAccessLevel(34) { it == images || it == video })
        assertEquals(MediaAccessLevel.FULL, mediaAccessLevel(34) { it == images || it == userSelected })
        assertEquals(MediaAccessLevel.LIMITED, mediaAccessLevel(34) { it == userSelected })
        assertEquals(MediaAccessLevel.FULL, mediaAccessLevel(33) { it == images })
        // USER_SELECTED means nothing before API 34.
        assertEquals(MediaAccessLevel.DENIED, mediaAccessLevel(33) { it == userSelected })
        assertEquals(MediaAccessLevel.FULL, mediaAccessLevel(30) { it == external })
        assertEquals(MediaAccessLevel.DENIED, mediaAccessLevel(30, none))
    }

    @Test
    fun partialGrantEndsTheFlowInsteadOfRepromptingEveryLaunch() {
        val (notif, media) = runtimePermissionBatches(34)
        val action = nextPermissionAction(
            PermissionBatch.MEDIA,
            notif,
            media,
            hasPermission = { it == userSelected || it == location },
        )
        assertEquals(PermissionAction.MarkDone, action)
    }

    @Test
    fun partialGrantStillAsksForTheLocationPermission() {
        val (notif, media) = runtimePermissionBatches(34)
        val action = nextPermissionAction(
            PermissionBatch.MEDIA,
            notif,
            media,
            hasPermission = { it == userSelected },
        )
        assertEquals(PermissionAction.LaunchMedia(listOf(location)), action)
    }

    @Test
    fun existingFullGrantPicksUpTheLocationPermission() {
        // An install that granted media before ACCESS_MEDIA_LOCATION was
        // declared: only the new permission is requested.
        val (notif, media) = runtimePermissionBatches(33)
        val action = nextPermissionAction(
            PermissionBatch.MEDIA,
            notif,
            media,
            hasPermission = { it == images || it == video },
        )
        assertEquals(PermissionAction.LaunchMedia(listOf(location)), action)
    }

    @Test
    fun fullGrantDoesNotRequestTheUserSelectedMarkerAlone() {
        val (notif, media) = runtimePermissionBatches(34)
        val action = nextPermissionAction(
            PermissionBatch.MEDIA,
            notif,
            media,
            hasPermission = { it == images || it == video || it == location },
        )
        assertEquals(PermissionAction.MarkDone, action)
    }

    @Test
    fun nothingGrantedRequestsTheWholeBatchTogether() {
        // USER_SELECTED must be in the same request as IMAGES/VIDEO for the
        // system to offer "Select photos".
        val (notif, media) = runtimePermissionBatches(34)
        val action = nextPermissionAction(PermissionBatch.MEDIA, notif, media, hasPermission = { false })
        assertEquals(PermissionAction.LaunchMedia(listOf(images, video, userSelected, location)), action)
    }

    @Test
    fun reselectRequestsTheReadPermissionsAndTheMarker() {
        val (_, media) = runtimePermissionBatches(34)
        val reselect = mediaReselectPermissions(media)
        assertEquals(listOf(images, video, userSelected), reselect)
        assertFalse(location in reselect)
        assertTrue(mediaReselectPermissions(runtimePermissionBatches(28).second) == listOf(external))
    }
}
