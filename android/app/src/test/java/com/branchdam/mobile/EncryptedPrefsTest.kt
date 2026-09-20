package com.branchdam.mobile

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for [EncryptedPrefs] — the T2-5 hardening wrapper that
 * produces EncryptedSharedPreferences for the API key, server URL,
 * and agent ID.
 *
 * What we verify on the JVM:
 *   - The helper exposes the documented file name and master-key
 *     alias so the QR pairing flow can target them.
 *   - The shared constants don't drift away from the production
 *     strings the field issue (#69) and the F plan call out.
 *   - The masterKeyBuilder test seam pins failure handling deterministically.
 */
class EncryptedPrefsTest {

    @Test
    fun testMasterKeyAliasIsStable() {
        assertEquals("branchdam_master_key", EncryptedPrefs.MASTER_KEY_ALIAS)
    }

    @Test
    fun testSecurePrefsFileNameIsStable() {
        assertEquals("branchdam_secure_prefs", EncryptedPrefs.SECURE_PREFS_NAME)
    }

    @Test
    fun testSecurePrefsFileNameIsDistinctFromPlainPrefsName() {
        assertTrue(EncryptedPrefs.SECURE_PREFS_NAME != BranchDamApplication.PREFS_NAME)
    }

    @Test
    fun testIsEncryptedStorageAvailable_ReturnsFalseWhenMasterKeyFails() {
        val context = mock<Context>()
        val originalBuilder = EncryptedPrefs.masterKeyBuilder
        try {
            EncryptedPrefs.resetCacheForTesting()
            EncryptedPrefs.masterKeyBuilder = { _, _ -> throw RuntimeException("Keystore unavailable") }
            assertFalse(EncryptedPrefs.isEncryptedStorageAvailable(context))
        } finally {
            EncryptedPrefs.masterKeyBuilder = originalBuilder
            EncryptedPrefs.resetCacheForTesting()
        }
    }
}
