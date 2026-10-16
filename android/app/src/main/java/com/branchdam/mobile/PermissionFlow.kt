package com.branchdam.mobile

import android.Manifest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Pure permission-flow logic, kept free of Compose and Activity types so
// it can be unit-tested on the JVM. [DrivePermissionFlow] in
// MainActivity.kt is the Composable that executes these decisions.

/**
 * Tracks which permission batch is currently pending. The flow is:
 *
 * ```
 * NONE → NOTIFICATIONS → MEDIA → DONE
 * ```
 *
 * `DONE` is the terminal state and short-circuits the
 * [DrivePermissionFlow] `LaunchedEffect`, so a user who permanently
 * denies a permission (which on Android 13+ returns the launcher
 * callback immediately with `false`) cannot cause the state machine to
 * loop forever. The pre-fix design over-loaded `MEDIA` as a de-facto
 * terminal state and reset to `NONE` after every media-batch callback,
 * which meant a user denying everything would re-trigger the launchers
 * on every Compose recomposition. See
 * `PermissionFlowStateTest.testDoneStateIsTerminal` for the
 * regression guard.
 */
enum class PermissionBatch {
    NONE,
    NOTIFICATIONS,
    MEDIA,
    DONE,
}

class PermissionFlowState {
    private val _batch = MutableStateFlow(PermissionBatch.NONE)
    val batch: StateFlow<PermissionBatch> = _batch.asStateFlow()

    fun nextBatch() {
        _batch.value = when (_batch.value) {
            PermissionBatch.NONE -> PermissionBatch.NOTIFICATIONS
            PermissionBatch.NOTIFICATIONS -> PermissionBatch.MEDIA
            PermissionBatch.MEDIA -> PermissionBatch.DONE
            PermissionBatch.DONE -> PermissionBatch.DONE
        }
    }

    fun markDone() {
        _batch.value = PermissionBatch.DONE
    }
}

/**
 * Returns the runtime permissions the app needs, partitioned by API
 * level. Pre-API 33 (Tiramisu) reads media via
 * [Manifest.permission.READ_EXTERNAL_STORAGE]; API 33+ reads media via
 * [Manifest.permission.READ_MEDIA_IMAGES] /
 * [Manifest.permission.READ_MEDIA_VIDEO]. API 34+ also asks for
 * [Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] in the same
 * request, which is how an app opts in to the "Select photos" partial
 * grant. API 29+ adds [Manifest.permission.ACCESS_MEDIA_LOCATION] so
 * uploads can read the unredacted original (GPS EXIF intact).
 * `POST_NOTIFICATIONS` is only meaningful on API 33+; earlier versions
 * grant it implicitly.
 *
 * Pure function of [sdkInt] so unit tests can exercise every API level
 * without instantiating an Activity.
 */
internal fun runtimePermissionBatches(sdkInt: Int = Build.VERSION.SDK_INT): Pair<List<String>, List<String>> {
    val notifications = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }
    val media = buildList {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        if (sdkInt >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    return notifications to media
}

/**
 * Media permissions that ride along with the read permissions but do not
 * by themselves decide whether the app can see the library: the partial
 * access marker and the unredacted-location permission.
 */
private val MEDIA_COMPANION_PERMISSIONS = setOf(
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

/** How much of the photo library the app can read. */
enum class MediaAccessLevel {
    /** Every photo and video. */
    FULL,

    /** API 34+ "Select photos": only the items the user picked. */
    LIMITED,

    /** No media access. */
    DENIED,
}

/**
 * Pure classification of the current media grant, following the
 * platform's partial-access guidance: READ_MEDIA_IMAGES or
 * READ_MEDIA_VIDEO means full access on API 33+; otherwise
 * READ_MEDIA_VISUAL_USER_SELECTED means the user picked specific items
 * (API 34+); before API 33 READ_EXTERNAL_STORAGE is all-or-nothing.
 */
internal fun mediaAccessLevel(sdkInt: Int, hasPermission: (String) -> Boolean): MediaAccessLevel = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        (hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
            hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccessLevel.FULL
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccessLevel.LIMITED
    sdkInt < Build.VERSION_CODES.TIRAMISU &&
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccessLevel.FULL
    else -> MediaAccessLevel.DENIED
}

/**
 * The permissions to request when the user asks to change a "Select
 * photos" grant: the read permissions plus the partial-access marker, so
 * the system shows its "Select more photos / Allow all" dialog again.
 */
internal fun mediaReselectPermissions(mediaBatch: List<String>): List<String> =
    mediaBatch.filter { it != Manifest.permission.ACCESS_MEDIA_LOCATION }

/**
 * Pure decision function: given the current state and the runtime
 * batches, decide what action to take next. Extracted from
 * [DrivePermissionFlow] so unit tests can exercise the sequencing
 * logic without instantiating a Compose runtime.
 *
 * Returns the next [PermissionBatch] state the flow should transition
 * to. `null` means "stay where you are" (used when a launcher has
 * fired and is awaiting the user's callback).
 *
 * The "already granted" filter is applied to *both* batches — without
 * it, a user who has already granted `POST_NOTIFICATIONS` would see
 * one no-op dialog fire after the first cycle before settling. (The
 * pre-fix `MEDIA`-only filter meant a `POST_NOTIFICATIONS` denial
 * would cause a re-launch.)
 */
internal fun nextPermissionAction(
    batch: PermissionBatch,
    notificationsBatch: List<String>,
    mediaBatch: List<String>,
    hasPermission: (String) -> Boolean,
): PermissionAction {
    return when (batch) {
        PermissionBatch.NONE -> PermissionAction.Advance
        PermissionBatch.NOTIFICATIONS -> {
            if (notificationsBatch.isEmpty()) {
                PermissionAction.Advance
            } else {
                val missing = notificationsBatch.filter { !hasPermission(it) }
                if (missing.isEmpty()) {
                    PermissionAction.Advance
                } else {
                    PermissionAction.LaunchNotifications(missing)
                }
            }
        }
        PermissionBatch.MEDIA -> {
            val readPermissions = mediaBatch.filter { it !in MEDIA_COMPANION_PERMISSIONS }
            val readMissing = readPermissions.filter { !hasPermission(it) }
            val limitedGrant = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in mediaBatch &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            if (readMissing.isEmpty() || limitedGrant) {
                // Full access, or the user chose "Select photos": that is
                // a grant, not a denial, so do not re-prompt on every
                // launch (the gallery banner offers re-selection). Only
                // the location permission may still be outstanding; it
                // is granted without a dialog alongside media access.
                val locationMissing = mediaBatch.filter {
                    it == Manifest.permission.ACCESS_MEDIA_LOCATION && !hasPermission(it)
                }
                if (locationMissing.isEmpty()) {
                    PermissionAction.MarkDone
                } else {
                    PermissionAction.LaunchMedia(locationMissing)
                }
            } else {
                PermissionAction.LaunchMedia(mediaBatch.filter { !hasPermission(it) })
            }
        }
        PermissionBatch.DONE -> PermissionAction.Stay
    }
}

internal sealed class PermissionAction {
    object Advance : PermissionAction()
    object MarkDone : PermissionAction()
    object Stay : PermissionAction()
    data class LaunchNotifications(val perms: List<String>) : PermissionAction()
    data class LaunchMedia(val perms: List<String>) : PermissionAction()
}
