package com.branchdam.mobile

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [BranchDamApplication.readEngineConfig] — the config
 * reader extracted from the Application's initCoreEngine. Uses a
 * mocked SharedPreferences to verify default values, override values,
 * and the "apiKey from EncryptedSharedPreferences" path (same
 * SharedPreferences contract, different storage backend).
 */
class BranchDamApplicationTest {

    @Test
    fun testReadEngineConfig_Defaults() {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, BranchDamApplication.DEFAULT_SERVER_URL))
            .thenReturn(null)
        whenever(prefs.getString(BranchDamApplication.KEY_API_KEY, ""))
            .thenReturn(null)
        whenever(prefs.getString(eq(BranchDamApplication.KEY_AGENT_ID), any()))
            .thenReturn(null)

        val context = mockContext(prefs)
        val config = BranchDamApplication.readEngineConfig(context)

        assertEquals(BranchDamApplication.DEFAULT_SERVER_URL, config.serverUrl)
        assertEquals("", config.apiKey)
        assertEquals(
            BranchDamApplication.DEFAULT_AGENT_ID_PREFIX + android.os.Build.MODEL,
            config.agentId
        )
    }

    @Test
    fun testReadEngineConfig_Overrides() {
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, BranchDamApplication.DEFAULT_SERVER_URL))
            .thenReturn("https://nas.example.com:8443")
        whenever(prefs.getString(BranchDamApplication.KEY_API_KEY, ""))
            .thenReturn("supersecret-key-123")
        whenever(prefs.getString(eq(BranchDamApplication.KEY_AGENT_ID), any()))
            .thenReturn("custom-agent-id")

        val context = mockContext(prefs)
        val config = BranchDamApplication.readEngineConfig(context)

        assertEquals("https://nas.example.com:8443", config.serverUrl)
        assertEquals("supersecret-key-123", config.apiKey)
        assertEquals("custom-agent-id", config.agentId)
    }

    @Test
    fun testReadEngineConfig_BadBaseURLReturnsIt() {
        // The config reader does not validate the URL — validation
        // happens in the engine's NewEngine. A bad URL is passed
        // through and surfaces as an engine init failure (the app
        // shows a banner per the F plan).
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, BranchDamApplication.DEFAULT_SERVER_URL))
            .thenReturn("not-a-url")
        whenever(prefs.getString(BranchDamApplication.KEY_API_KEY, ""))
            .thenReturn("")
        whenever(prefs.getString(eq(BranchDamApplication.KEY_AGENT_ID), any()))
            .thenReturn(null)

        val context = mockContext(prefs)
        val config = BranchDamApplication.readEngineConfig(context)

        assertEquals("not-a-url", config.serverUrl)
    }

    @Test
    fun testReadEngineConfig_ApiKeyFromEncryptedPrefs() {
        // The F plan calls for the apiKey to be stored in
        // EncryptedSharedPreferences. The read path is identical to
        // regular SharedPreferences (both implement the same
        // interface); this test verifies the contract is honored.
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString(BranchDamApplication.KEY_SERVER_URL, BranchDamApplication.DEFAULT_SERVER_URL))
            .thenReturn(null)
        whenever(prefs.getString(BranchDamApplication.KEY_API_KEY, ""))
            .thenReturn("encrypted-key-from-secure-storage")
        whenever(prefs.getString(eq(BranchDamApplication.KEY_AGENT_ID), any()))
            .thenReturn(null)

        val context = mockContext(prefs)
        val config = BranchDamApplication.readEngineConfig(context)

        assertEquals("encrypted-key-from-secure-storage", config.apiKey)
    }

    private fun mockContext(prefs: SharedPreferences): Context {
        val context = mock<Context>()
        whenever(context.getSharedPreferences(BranchDamApplication.PREFS_NAME, Context.MODE_PRIVATE))
            .thenReturn(prefs)
        return context
    }
}
