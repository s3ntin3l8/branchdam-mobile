package com.branchdam.mobile

import android.content.SharedPreferences

/**
 * One-time SharedPreferences key migration for the T2-10
 * `branchdam_` prefix rollout.
 *
 * Pre-T2-10 Android preference keys (`sync_on_mobile_data`,
 * `auto_import_camera_roll`) lacked the `branchdam_` prefix that
 * the iOS shell uses. Adding the prefix without preserving the
 * legacy values would silently reset every existing user's
 * settings. This helper copies each legacy value into its new
 * canonical key and removes the legacy entry so the rest of the
 * app can read the canonical keys exclusively.
 *
 * Idempotency contract:
 *   - Legacy key absent  -> no put, no remove (no-op).
 *   - New key already set -> legacy key removed only; new key
 *     preserved (the user's most recent explicit choice wins).
 *   - Called repeatedly  -> safe; second call finds legacy keys
 *     already removed and exits without writing.
 *
 * Silent: missing keys are ignored, no exceptions are raised.
 * Wired into [BranchDamApplication.onCreate] so every read of the
 * canonical keys sees the user's previous settings before any
 * UI or worker runs.
 */
object PrefKeyMigration {

    /**
     * Legacy key for "sync on mobile data" (pre-T2-10 Android,
     * no `branchdam_` prefix).
     */
    const val LEGACY_SYNC_ON_MOBILE_DATA = "sync_on_mobile_data"

    /**
     * Canonical key for "sync on mobile data" — matches iOS
     * `BackgroundSyncManager.keySyncOnMobileData`.
     */
    const val LEGACY_SYNC_ON_MOBILE_DATA_NEW = BranchDamKeys.SYNC_ON_MOBILE_DATA

    /**
     * Legacy key for "auto-import camera roll" (pre-T2-10
     * Android, no `branchdam_` prefix).
     */
    const val LEGACY_AUTO_IMPORT_CAMERA_ROLL = "auto_import_camera_roll"

    /**
     * Canonical key for "auto-import camera roll" — matches iOS
     * `AppleCameraRollImportNotifier.keyAutoImportEnabled`.
     */
    const val LEGACY_AUTO_IMPORT_CAMERA_ROLL_NEW = BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL

    private data class BooleanMigration(val oldKey: String, val newKey: String)

    private val BOOLEAN_MIGRATIONS = listOf(
        BooleanMigration(LEGACY_SYNC_ON_MOBILE_DATA, LEGACY_SYNC_ON_MOBILE_DATA_NEW),
        BooleanMigration(LEGACY_AUTO_IMPORT_CAMERA_ROLL, LEGACY_AUTO_IMPORT_CAMERA_ROLL_NEW)
    )

    /**
     * Runs all registered migrations against [prefs]. Acquires a
     * single editor and applies it once so the migration is
     * atomic from the prefs file's perspective.
     */
    fun migrate(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var changed = false
        for (migration in BOOLEAN_MIGRATIONS) {
            if (!prefs.contains(migration.oldKey)) continue
            if (!prefs.contains(migration.newKey)) {
                editor.putBoolean(
                    migration.newKey,
                    prefs.getBoolean(migration.oldKey, false)
                )
            }
            editor.remove(migration.oldKey)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }
}