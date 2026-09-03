package com.branchdam.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * T2-10 migration tests.
 *
 * Pre-T2-10 Android preference keys (`sync_on_mobile_data`,
 * `auto_import_camera_roll`) lacked the `branchdam_` prefix that
 * the iOS shell uses. The migration helper copies a legacy value
 * into the new key and removes the legacy key on first launch
 * after the upgrade. It is silent, idempotent, and never
 * overwrites an already-set new key.
 *
 * The tests use a mocked SharedPreferences because the JVM unit
 * test environment runs with `isReturnDefaultValues = true`,
 * which would silently swallow calls into Android's
 * SharedPreferences implementation and hide real behaviour behind
 * default-returning stubs.
 */
class PrefKeyMigrationTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
    }

    @Test
    fun testMigratesLegacySyncOnMobileData() {
        // Pre-T2-10 install: only the legacy key is set.
        whenever(prefs.contains("sync_on_mobile_data")).thenReturn(true)
        whenever(prefs.contains(BranchDamKeys.SYNC_ON_MOBILE_DATA)).thenReturn(false)
        whenever(prefs.getBoolean("sync_on_mobile_data", false)).thenReturn(true)

        PrefKeyMigration.migrate(prefs)

        verify(editor).putBoolean(BranchDamKeys.SYNC_ON_MOBILE_DATA, true)
        verify(editor).remove("sync_on_mobile_data")
        verify(editor, never()).remove(BranchDamKeys.SYNC_ON_MOBILE_DATA)
        verify(editor).apply()
    }

    @Test
    fun testMigratesLegacyAutoImportCameraRoll() {
        whenever(prefs.contains("auto_import_camera_roll")).thenReturn(true)
        whenever(prefs.contains(BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL)).thenReturn(false)
        whenever(prefs.getBoolean("auto_import_camera_roll", false)).thenReturn(true)

        PrefKeyMigration.migrate(prefs)

        verify(editor).putBoolean(BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL, true)
        verify(editor).remove("auto_import_camera_roll")
        verify(editor).apply()
    }

    @Test
    fun testMigrationIsNoOpWhenNoLegacyKeysPresent() {
        // Fresh install or post-migration state: legacy keys absent.
        whenever(prefs.contains("sync_on_mobile_data")).thenReturn(false)
        whenever(prefs.contains("auto_import_camera_roll")).thenReturn(false)

        PrefKeyMigration.migrate(prefs)

        verify(editor, never()).putBoolean(any<String>(), any<Boolean>())
        verify(editor, never()).remove(any<String>())
    }

    @Test
    fun testMigrationIsIdempotent() {
        // First migration: legacy key is present and gets copied.
        // Second migration: legacy key is gone, nothing to do —
        // and the helper must not issue a redundant empty apply()
        // (apply() on an empty editor still queues a fsync, which is
        // pure cost at app startup).
        whenever(prefs.contains("sync_on_mobile_data"))
            .thenReturn(true)   // first call
            .thenReturn(false)  // second call (after remove)
        whenever(prefs.contains(BranchDamKeys.SYNC_ON_MOBILE_DATA)).thenReturn(false)
        whenever(prefs.getBoolean("sync_on_mobile_data", false)).thenReturn(true)

        PrefKeyMigration.migrate(prefs)
        PrefKeyMigration.migrate(prefs)

        // Only one putBoolean across both calls.
        verify(editor, times(1)).putBoolean(eq(BranchDamKeys.SYNC_ON_MOBILE_DATA), eq(true))
        verify(editor, times(1)).remove("sync_on_mobile_data")
        verify(editor, times(1)).apply()
    }

    @Test
    fun testNewKeyNotOverwrittenIfAlreadySet() {
        // A user has both keys set: e.g. legacy key written by a
        // pre-T2-10 process, then the new key set explicitly by the
        // current code. The new key wins; only the legacy key is
        // removed.
        whenever(prefs.contains("sync_on_mobile_data")).thenReturn(true)
        whenever(prefs.contains(BranchDamKeys.SYNC_ON_MOBILE_DATA)).thenReturn(true)
        whenever(prefs.getBoolean("sync_on_mobile_data", false)).thenReturn(true)

        PrefKeyMigration.migrate(prefs)

        verify(editor, never()).putBoolean(eq(BranchDamKeys.SYNC_ON_MOBILE_DATA), any<Boolean>())
        verify(editor).remove("sync_on_mobile_data")
        verify(editor).apply()
    }

    @Test
    fun testMigrationConstantMappingMatchesRegistry() {
        // Pinned here so a future rename of either side without the
        // other surfaces as a test failure.
        assertEquals(
            "sync_on_mobile_data",
            PrefKeyMigration.LEGACY_SYNC_ON_MOBILE_DATA
        )
        assertEquals(
            BranchDamKeys.SYNC_ON_MOBILE_DATA,
            PrefKeyMigration.LEGACY_SYNC_ON_MOBILE_DATA_NEW
        )
        assertEquals(
            "auto_import_camera_roll",
            PrefKeyMigration.LEGACY_AUTO_IMPORT_CAMERA_ROLL
        )
        assertEquals(
            BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL,
            PrefKeyMigration.LEGACY_AUTO_IMPORT_CAMERA_ROLL_NEW
        )
    }
}