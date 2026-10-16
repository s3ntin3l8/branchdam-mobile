package com.branchdam.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The API key is stored only in Keystore-backed encrypted prefs. When the
 * Keystore is unavailable it is refused, never written to plain prefs.
 */
class ConnectionSettingsStoreTest {

    private lateinit var plain: SharedPreferences
    private lateinit var plainEditor: SharedPreferences.Editor
    private lateinit var secure: SharedPreferences
    private lateinit var secureEditor: SharedPreferences.Editor

    private fun editorFor(prefs: SharedPreferences): SharedPreferences.Editor {
        val editor: SharedPreferences.Editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
        return editor
    }

    @Before
    fun setUp() {
        plain = mock()
        plainEditor = editorFor(plain)
        secure = mock()
        secureEditor = editorFor(secure)
    }

    @Test
    fun keystoreUnavailable_refusesTheKeyAndNeverWritesItInPlaintext() {
        val result = ConnectionSettingsStore.save(
            secure = null,
            plain = plain,
            serverUrl = "https://dam.example.com",
            apiKey = "bdam_secret", // pragma: allowlist secret
            agentId = "pixel-10",
        )

        assertEquals(ConnectionSettingsStore.SaveResult.KEY_STORAGE_UNAVAILABLE, result)
        assertNotNull(ConnectionSettingsStore.messageFor(result))
        verify(plainEditor, never()).putString(eq(BranchDamApplication.KEY_API_KEY), anyOrNull())
        // A plaintext key left behind by an older release is deleted.
        verify(plainEditor).remove(BranchDamApplication.KEY_API_KEY)
        // The non-secret fields are still saved.
        verify(plainEditor).putString(BranchDamApplication.KEY_SERVER_URL, "https://dam.example.com")
        verify(plainEditor).putString(BranchDamApplication.KEY_AGENT_ID, "pixel-10")
    }

    @Test
    fun keystoreUnavailable_withoutAKeyIsFine() {
        val result = ConnectionSettingsStore.save(null, plain, "https://dam.example.com", "", "pixel-10")
        assertEquals(ConnectionSettingsStore.SaveResult.SAVED, result)
        assertNull(ConnectionSettingsStore.messageFor(result))
        verify(plainEditor, never()).putString(eq(BranchDamApplication.KEY_API_KEY), anyOrNull())
    }

    @Test
    fun keystoreAvailable_storesTheKeyEncryptedOnly() {
        val result = ConnectionSettingsStore.save(secure, plain, "https://dam.example.com", "bdam_secret", "pixel-10")

        assertEquals(ConnectionSettingsStore.SaveResult.SAVED, result)
        verify(secureEditor).putString(BranchDamApplication.KEY_API_KEY, "bdam_secret")
        verify(secureEditor).commit()
        verify(plainEditor, never()).putString(eq(BranchDamApplication.KEY_API_KEY), anyOrNull())
        verify(plainEditor).remove(BranchDamApplication.KEY_API_KEY)
    }

    @Test
    fun encryptedWriteFailureIsReported() {
        whenever(secureEditor.commit()).thenReturn(false)
        val result = ConnectionSettingsStore.save(secure, plain, "https://dam.example.com", "bdam_secret", "pixel-10")
        assertEquals(ConnectionSettingsStore.SaveResult.WRITE_FAILED, result)
        assertNotNull(ConnectionSettingsStore.messageFor(result))
        verify(plainEditor, never()).putString(eq(BranchDamApplication.KEY_API_KEY), anyOrNull())
    }
}
