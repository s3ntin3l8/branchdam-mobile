package com.branchdam.mobile

import com.branchdam.mobile.ui.settings.EngineInit
import com.branchdam.mobile.ui.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsViewModelTest {

    @Before
    fun resetEngineInit() {
        SettingsViewModel.engineInit = { _, _, _, _, _, _ -> true }
    }

    @After
    fun restoreEngineInit() {
        SettingsViewModel.engineInit = com.branchdam.mobile.EngineHolder::initialize
    }

    @Test
    fun testValidateUrlBlankFails() {
        assertEquals("Server URL is required", SettingsViewModel.validateUrl("", isDebug = true))
    }

    @Test
    fun testValidateUrlHttpInReleaseFails() {
        assertNotNull(SettingsViewModel.validateUrl("http://example.com", isDebug = false))
    }

    @Test
    fun testValidateUrlHttpsPasses() {
        assertNull(SettingsViewModel.validateUrl("https://example.com", isDebug = true))
        assertNull(SettingsViewModel.validateUrl("https://example.com", isDebug = false))
    }

    @Test
    fun testValidateUrlLocalDevHostPassesInDebug() {
        assertNull(SettingsViewModel.validateUrl("http://10.0.2.2:8080", isDebug = true))
    }

    @Test
    fun testValidateUrlLocalDevHostFailsInRelease() {
        assertNotNull(SettingsViewModel.validateUrl("http://10.0.2.2:8080", isDebug = false))
    }

    @Test
    fun testEngineInitDefaultDelegatesToEngineHolder() {
        val original = SettingsViewModel.engineInit
        try {
            SettingsViewModel.engineInit = com.branchdam.mobile.EngineHolder::initialize
            assertEquals(com.branchdam.mobile.EngineHolder::initialize, SettingsViewModel.engineInit)
        } finally {
            SettingsViewModel.engineInit = original
        }
    }

    @Test
    fun testEngineInitSeamIsInvokable() {
        var called = false
        SettingsViewModel.engineInit = { _, _, _, _, _, _ ->
            called = true
            true
        }
        val result: Boolean = SettingsViewModel.engineInit(
            "dbPath", "https://x", "key", "agent", "0.1.0", "",
        )
        assertTrue(result)
        assertTrue(called)
    }

    @Test
    fun testEngineInitFailurePathReturnsFalse() {
        SettingsViewModel.engineInit = { _, _, _, _, _, _ -> false }
        val result: Boolean = SettingsViewModel.engineInit(
            "dbPath", "https://x", "key", "agent", "0.1.0", "",
        )
        assertFalse(result)
    }
}
