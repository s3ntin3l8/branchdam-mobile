package com.branchdam.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The engine only switches to a rotated API key once [RotatedApiKeyStore]
 * returns normally, so it must throw whenever the key is not durably in
 * encrypted storage, and must never write it anywhere else.
 *
 * Mocked SharedPreferences, like [PrefKeyMigrationTest]: the JVM unit
 * tests run with `isReturnDefaultValues = true`.
 */
class RotatedApiKeyStoreTest {

    private lateinit var securePrefs: SharedPreferences
    private lateinit var secureEditor: SharedPreferences.Editor

    private val rotatedKey = "bdk_rotated_fedcba9876543210fedcba9876543210" // pragma: allowlist secret

    @Before
    fun setUp() {
        securePrefs = mock()
        secureEditor = mock()
        whenever(securePrefs.edit()).thenReturn(secureEditor)
    }

    @Test
    fun storesKeyInEncryptedPrefsWithCommit() {
        whenever(secureEditor.commit()).thenReturn(true)
        whenever(securePrefs.getString(BranchDamApplication.KEY_API_KEY, null)).thenReturn(rotatedKey)

        RotatedApiKeyStore { securePrefs }.store(rotatedKey)

        verify(secureEditor).putString(BranchDamApplication.KEY_API_KEY, rotatedKey)
        verify(secureEditor).commit()
        verify(secureEditor, never()).apply()
    }

    @Test
    fun throwsWhenEncryptedStorageIsUnavailable() {
        val e = assertThrows(IllegalStateException::class.java) {
            RotatedApiKeyStore { null }.store(rotatedKey)
        }
        assertEquals(RotatedApiKeyStore.KEY_STORAGE_UNAVAILABLE, e.message)
    }

    @Test
    fun throwsWhenCommitFails() {
        whenever(secureEditor.commit()).thenReturn(false)

        assertThrows(IllegalStateException::class.java) {
            RotatedApiKeyStore { securePrefs }.store(rotatedKey)
        }
    }

    @Test
    fun throwsWhenReadBackDiffers() {
        whenever(secureEditor.commit()).thenReturn(true)
        whenever(securePrefs.getString(BranchDamApplication.KEY_API_KEY, null)).thenReturn("previous-key")

        assertThrows(IllegalStateException::class.java) {
            RotatedApiKeyStore { securePrefs }.store(rotatedKey)
        }
    }

    @Test
    fun refusesEmptyKeyWithoutWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            RotatedApiKeyStore { securePrefs }.store("")
        }
        verify(secureEditor, never()).putString(any(), anyOrNull())
    }
}
