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
 * T2-10 / T2-5 migration tests.
 *
 * Pre-T2-10 Android preference keys (`sync_on_mobile_data`,
 * `auto_import_camera_roll`) lacked the `branchdam_` prefix that
 * the iOS shell uses. The migration helper copies a legacy value
 * into the new key and removes the legacy key on first launch
 * after the upgrade. It is silent, idempotent, and never
 * overwrites an already-set new key.
 *
 * T2-5: secrets (server_url, api_key, agent_id) found in plain
 * SharedPreferences are migrated to EncryptedSharedPreferences
 * and removed from plain prefs.
 *
 * The tests use mocked SharedPreferences because the JVM unit
 * test environment runs with `isReturnDefaultValues = true`,
 * which would silently swallow calls into Android's
 * SharedPreferences implementation and hide real behaviour behind
 * default-returning stubs.
 */
class PrefKeyMigrationTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var securePrefs: SharedPreferences
    private lateinit var secureEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)

        securePrefs = mock()
        secureEditor = mock()
        whenever(securePrefs.edit()).thenReturn(secureEditor)
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

    // --- T2-5 secret migration tests ---

    @Test
    fun testSecretMigrationCopiesToEncryptedPrefs() {
        // Pre-T2-5 install: secrets in plain prefs should be
        // migrated to encrypted prefs and removed from plain.
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null))
            .thenReturn("https://example.com")
        whenever(prefs.contains(BranchDamApplication.KEY_API_KEY)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_API_KEY, null))
            .thenReturn("test-key")  // pragma: allowlist secret
        whenever(prefs.contains(BranchDamApplication.KEY_AGENT_ID)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_AGENT_ID, null))
            .thenReturn("test-agent")

        PrefKeyMigration.migrate(prefs, securePrefs)

        // Secrets copied to encrypted prefs
        verify(secureEditor).putString(BranchDamApplication.KEY_SERVER_URL, "https://example.com")
        verify(secureEditor).putString(BranchDamApplication.KEY_API_KEY, "test-key")
        verify(secureEditor).putString(BranchDamApplication.KEY_AGENT_ID, "test-agent")
        verify(secureEditor).apply()

        // Secrets removed from plain prefs
        verify(editor).remove(BranchDamApplication.KEY_SERVER_URL)
        verify(editor).remove(BranchDamApplication.KEY_API_KEY)
        verify(editor).remove(BranchDamApplication.KEY_AGENT_ID)
        verify(editor).apply()
    }

    @Test
    fun testSecretMigrationSkipsMissingKeys() {
        // Only server_url is present; api_key and agent_id are absent.
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null))
            .thenReturn("https://example.com")
        whenever(prefs.contains(BranchDamApplication.KEY_API_KEY)).thenReturn(false)
        whenever(prefs.contains(BranchDamApplication.KEY_AGENT_ID)).thenReturn(false)

        PrefKeyMigration.migrate(prefs, securePrefs)

        verify(secureEditor).putString(eq(BranchDamApplication.KEY_SERVER_URL), eq("https://example.com"))
        verify(secureEditor, never()).putString(eq(BranchDamApplication.KEY_API_KEY), any())
        verify(secureEditor, never()).putString(eq(BranchDamApplication.KEY_AGENT_ID), any())
        verify(secureEditor).apply()
    }

    @Test
    fun testSecretMigrationSkipsNullValues() {
        // Key exists but getString returns null (corrupted prefs).
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null)).thenReturn(null)

        PrefKeyMigration.migrate(prefs, securePrefs)

        verify(secureEditor, never()).putString(any(), any())
        verify(editor).remove(BranchDamApplication.KEY_SERVER_URL)
    }

    @Test
    fun testSecretMigrationSkippedWhenEncryptedPrefsNull() {
        // Keystore failure: encryptedPrefs is null — migration
        // should be silently skipped without crashing. The plain
        // prefs key is NOT removed because the secret migration
        // block is entirely skipped.
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null))
            .thenReturn("https://example.com")

        PrefKeyMigration.migrate(prefs, null)

        // No crash, no secret operations, key stays in plain prefs
        verify(editor, never()).remove(BranchDamApplication.KEY_SERVER_URL)
        verify(editor, never()).apply()
    }

    @Test
    fun testSecretMigrationIsIdempotent() {
        // First call: key is in plain prefs, migrated and removed.
        // Second call: key is gone from plain prefs, no-op.
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL))
            .thenReturn(true)   // first call
            .thenReturn(false)  // second call (after remove)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null))
            .thenReturn("https://example.com")

        PrefKeyMigration.migrate(prefs, securePrefs)
        PrefKeyMigration.migrate(prefs, securePrefs)

        // Only one putString across both calls.
        verify(secureEditor, times(1)).putString(
            eq(BranchDamApplication.KEY_SERVER_URL),
            eq("https://example.com")
        )
        verify(secureEditor, times(1)).apply()
    }

    @Test
    fun testBooleanAndSecretMigrationsComposeCorrectly() {
        // Both legacy boolean keys and secret keys are present.
        whenever(prefs.contains("sync_on_mobile_data")).thenReturn(true)
        whenever(prefs.contains(BranchDamKeys.SYNC_ON_MOBILE_DATA)).thenReturn(false)
        whenever(prefs.getBoolean("sync_on_mobile_data", false)).thenReturn(true)
        whenever(prefs.contains(BranchDamApplication.KEY_SERVER_URL)).thenReturn(true)
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, null))
            .thenReturn("https://example.com")

        PrefKeyMigration.migrate(prefs, securePrefs)

        // Boolean migration applied
        verify(editor).putBoolean(BranchDamKeys.SYNC_ON_MOBILE_DATA, true)
        verify(editor).remove("sync_on_mobile_data")
        // Secret migration applied
        verify(editor).remove(BranchDamApplication.KEY_SERVER_URL)
        verify(secureEditor).putString(BranchDamApplication.KEY_SERVER_URL, "https://example.com")
        verify(editor).apply()
        verify(secureEditor).apply()
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
