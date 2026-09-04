package com.branchdam.mobile

/**
 * Canonical SharedPreferences key registry for the Android shell.
 *
 * The `branchdam_` prefix matches the iOS shell's UserDefaults keys
 * (see ios/BranchDAM/Ingest/BackgroundSyncManager.swift and
 * AppleCameraRollImportNotifier.swift) so a future migration tool
 * or shared preferences inspector can target the same string on
 * both platforms without having to learn two spellings.
 *
 * iOS parity:
 *   BackgroundSyncManager.keySyncOnMobileData
 *       == BranchDamKeys.SYNC_ON_MOBILE_DATA
 *   AppleCameraRollImportNotifier.keyAutoImportEnabled
 *       == BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL
 *
 * T2-10 hardening: pre-T2-10 Android keys (`sync_on_mobile_data`,
 * `auto_import_camera_roll`) lacked the `branchdam_` prefix.
 * Adding the prefix required a one-time migration: on first launch
 * after the upgrade, the legacy keys are read, copied into the new
 * keys, and removed. See [PrefKeyMigration] for the migration
 * helper and the unit test that covers the silent, idempotent
 * copy-and-delete.
 */
object BranchDamKeys {

    /** SharedPreferences file name for non-sensitive preferences. */
    const val PREFS_NAME = "branchdam_prefs"

    /**
     * Sync scheduler: whether to use mobile (cellular) data for
     * one-off sync requests. Read by [com.branchdam.mobile.service.SyncScheduler]
     * when computing WorkManager network constraints.
     */
    const val SYNC_ON_MOBILE_DATA = "branchdam_sync_on_mobile_data"

    /**
     * Camera roll import: whether to auto-enqueue newly-detected
     * photos for upload. Read by
     * [com.branchdam.mobile.service.ImportConfirmationNotifier] when
     * deciding whether to show a confirmation notification.
     */
    const val AUTO_IMPORT_CAMERA_ROLL = "branchdam_auto_import_camera_roll"

    /**
     * Sync status: timestamp (ms since epoch) of the last completed
     * sync cycle. Written by [com.branchdam.mobile.ui.sync.SyncStatusViewModel]
     * after observing a successful [androidx.work.WorkInfo.State.SUCCEEDED].
     */
    const val LAST_SYNC_TIME = "branchdam_last_sync_time"
}
