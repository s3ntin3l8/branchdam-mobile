package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EncryptedPrefs] — the T2-5 hardening wrapper that
 * produces EncryptedSharedPreferences for the API key, server URL,
 * and agent ID.
 *
 * The actual encryption code path depends on the Android Keystore
 * and the androidx.security library, neither of which is exercisable
 * on a JVM test classpath (this project uses
 * `testOptions.unitTests.isReturnDefaultValues = true`). The
 * production encryption path is exercised by the
 * instrumentation-test target and on-device smoke tests.
 *
 * What we *can* verify on the JVM:
 *   - The helper exposes the documented file name and master-key
 *     alias so the QR pairing flow can target them.
 *   - The shared constants don't drift away from the production
 *     strings the field issue (#69) and the F plan call out.
 */
class EncryptedPrefsTest {

    @Test
    fun testMasterKeyAliasIsStable() {
        // The master-key alias is the identifier the Android Keystore
        // uses to look up the symmetric key on subsequent launches.
        // Changing it would invalidate existing secrets on user
        // devices, forcing a re-pair.
        assertEquals("branchdam_master_key", EncryptedPrefs.MASTER_KEY_ALIAS)
    }

    @Test
    fun testSecurePrefsFileNameIsStable() {
        // Same reasoning as the master-key alias: changing the file
        // name would orphan existing encrypted entries. Pinned here
        // so the contract is explicit.
        assertEquals("branchdam_secure_prefs", EncryptedPrefs.SECURE_PREFS_NAME)
    }

    @Test
    fun testSecurePrefsFileNameIsDistinctFromPlainPrefsName() {
        // The T2-5 design keeps non-sensitive fields (sync-on-mobile-
        // data, auto-import toggle) in plain SharedPreferences under
        // BranchDamApplication.PREFS_NAME. Verify the encrypted file
        // uses a different name so the two never collide.
        assertTrue(EncryptedPrefs.SECURE_PREFS_NAME != BranchDamApplication.PREFS_NAME)
    }
}
