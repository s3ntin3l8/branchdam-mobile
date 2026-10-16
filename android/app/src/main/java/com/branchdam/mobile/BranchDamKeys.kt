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
     * sync cycle. Written by [com.branchdam.mobile.service.SyncWorker]
     * when a sync batch succeeds; read (never written) by
     * [com.branchdam.mobile.ui.sync.SyncStatusViewModel].
     */
    const val LAST_SYNC_TIME = "branchdam_last_sync_time"

    /**
     * Sync status: timestamp (ms since epoch) of the last sync the
     * server refused because it rejected this device's credentials
     * (core error AUTH_FAILED), or absent/0 when the last sync did not
     * fail that way. Written by [com.branchdam.mobile.service.SyncWorker]
     * (set on AUTH_FAILED, cleared on success) so the Sync screen can
     * ask the user to re-pair the device.
     */
    const val SYNC_AUTH_FAILED_AT = "branchdam_sync_auth_failed_at"

    /**
     * Trash sync: newline-separated content URIs of recycle-bin items
     * whose delete event was already handled. Read and written by
     * [com.branchdam.mobile.triage.ProcessedTrashStore].
     */
    const val TRASH_PROCESSED_URIS = "branchdam_trash_processed_uris"

    /**
     * The pending-import local IDs the confirmation notification currently
     * announces (it is replaced, never stacked). Its actions apply to
     * exactly these rows, so a row staged in the same second the
     * notification went up is never swept by a Skip the user tapped
     * without seeing it.
     */
    const val IMPORT_ANNOUNCED_IDS = "branchdam_import_announced_ids"

    /**
     * Background sync interval in minutes. Read by [com.branchdam.mobile.service.SyncScheduler]
     * when scheduling periodic WorkManager jobs.
     */
    const val SYNC_INTERVAL_MINUTES = "branchdam_sync_interval_minutes"

    /**
     * Whether to allow background sync when battery is low. When true,
     * the battery-not-low constraint is removed from periodic WorkManager jobs.
     * Read by [com.branchdam.mobile.service.SyncScheduler].
     */
    const val SYNC_ON_BATTERY_ONLY = "branchdam_sync_on_battery_only"

    /**
     * Number of items to process per sync batch. Passed to
     * [EngineHolder.syncBatch] by [com.branchdam.mobile.service.SyncWorker].
     */
    const val UPLOAD_BATCH_SIZE = "branchdam_upload_batch_size"

    /**
     * Maximum seconds to wait for a sync batch to complete. Passed to
     * [EngineHolder.syncBatch] by [com.branchdam.mobile.service.SyncWorker].
     */
    const val SYNC_TIMEOUT_SECS = "branchdam_sync_timeout_secs"

    /**
     * Debounce window (milliseconds) for MediaStore change callbacks.
     * Read by [com.branchdam.mobile.observer.MediaStoreObserver].
     */
    const val OBSERVER_DEBOUNCE_MS = "branchdam_observer_debounce_ms"

    /**
     * Timestamp (seconds since epoch) of the last completed MediaStore
     * scan. Read by [com.branchdam.mobile.observer.MediaStoreObserver]
     * on startup to resume scanning from where it left off.
     */
    const val OBSERVER_LAST_SCANNED_TIMESTAMP = "branchdam_observer_last_scanned_timestamp"

    /**
     * User-selected color-scheme mode (System / Light / Dark).
     * Stored as the lowercase [com.branchdam.mobile.ui.theme.ThemeMode]
     * name (`"system"` / `"light"` / `"dark"`).
     *
     * iOS parity: the iOS shell will use this exact spelling for
     * the matching `UserDefaults` key when its theme selector is
     * implemented in a follow-up PR, so a future cross-platform
     * sync tool can target one string.
     */
    const val THEME_MODE = "branchdam_theme_mode"

    /**
     * Set of folder names configured by the user to display in Gallery view.
     */
    const val INCLUDED_GALLERY_FOLDERS = "branchdam_included_gallery_folders"

    /**
     * Whether the user has completed the onboarding flow.
     */
    const val ONBOARDING_COMPLETED = "branchdam_onboarding_completed"

    /** Default background sync interval in minutes. */
    const val DEFAULT_SYNC_INTERVAL_MINUTES = 15

    /** Default number of items per sync batch. */
    const val DEFAULT_UPLOAD_BATCH_SIZE = 10

    /** Default maximum seconds to wait for a sync batch to complete. */
    const val DEFAULT_SYNC_TIMEOUT_SECS = 120

    /** Default debounce window for MediaStore observer (ms). */
    const val DEFAULT_OBSERVER_DEBOUNCE_MS = 500L
}
