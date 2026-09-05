package com.branchdam.mobile

import com.branchdam.mobile.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests that [SettingsViewModel.validateUrl] correctly accepts
 * HTTPS and local dev hosts in debug, and rejects plain HTTP and
 * blank URLs. `applyPairingConfig` itself requires an Android
 * Application context (it's an AndroidViewModel) and is not
 * directly exercised here — the QrScanViewModel flow tests that
 * the parser → ApplyResult.Applied path works, but populating
 * the SettingsViewModel fields downstream is covered by
 * instrumentation tests.
 */
class SettingsViewModelPairingTest {

    private fun validate(url: String): String? =
        SettingsViewModel.validateUrl(url, isDebug = true)

    @Test
    fun testValidateAcceptsLocalDevHost() {
        assertNull(validate("http://10.0.2.2:8080"))
        assertNull(validate("http://localhost:8080"))
    }

    @Test
    fun testValidateAcceptsHttps() {
        assertNull(validate("https://example.com:8080"))
    }

    @Test
    fun testValidateRejectsBlankUrl() {
        assertEquals("Server URL is required", validate(""))
        assertEquals("Server URL is required", validate("   "))
    }

    @Test
    fun testValidateRejectsHttpInRelease() {
        val error = SettingsViewModel.validateUrl("http://example.com:8080", isDebug = false)
        assertEquals("URL must use HTTPS", error)
    }
}
