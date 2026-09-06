package com.branchdam.mobile.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.ui.theme.ThemeMode
import com.branchdam.mobile.ui.theme.toStorageString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that [SettingsViewModel]'s `themeMode` StateFlow and
 * `setThemeMode` setter are correctly wired to the underlying
 * [com.branchdam.mobile.ui.theme.ThemePreferences]:
 *   - Default value when no key has been stored is [ThemeMode.SYSTEM].
 *   - A pre-seeded SharedPreferences value is reflected in the
 *     initial `themeMode.value`.
 *   - [SettingsViewModel.setThemeMode] updates both the StateFlow
 *     and the persisted SharedPreferences string.
 *   - The setter does not touch unrelated preference keys.
 *
 * Each [SettingsViewModel] constructs its own [com.branchdam.mobile.ui.theme.ThemePreferences]
 * bound to the canonical prefs file, so per-test isolation is
 * automatic — the ViewModel always reads the current state of the
 * backing file at construction time. The `@Before` clear() in this
 * class additionally defends against Robolectric configuration
 * shapes that share prefs storage across `@Test` methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelThemeTest {

    @Before
    fun setUp() {
        // Suppress the engine handshake that init { checkConnection() }
        // would otherwise trigger; the gomobile binding is unavailable
        // in JVM tests.
        SettingsViewModel.testConnectionFn = { true }

        // Defensive: clear the canonical prefs so a previous test's
        // value cannot leak into the initial-value assertions below.
        canonicalPrefs().edit().clear().apply()
    }

    @After
    fun tearDown() {
        SettingsViewModel.testConnectionFn = {
            com.branchdam.mobile.EngineHolder.testConnection()
        }
    }

    private fun context(): Context =
        ApplicationProvider.getApplicationContext()

    private fun canonicalPrefs() =
        context().getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private fun newViewModel(): SettingsViewModel =
        // ApplicationProvider.getApplicationContext() infers T=Application
        // from SettingsViewModel's constructor parameter type. Same
        // pattern used by SettingsViewModelRecheckTest.
        SettingsViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun testInitialThemeModeIsSystem_WhenNoKeyStored() {
        assertEquals(null, canonicalPrefs().getString(BranchDamKeys.THEME_MODE, null))
        val vm = newViewModel()
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun testInitialThemeModeMirrorsStoredValue() {
        // Pre-seed LIGHT before constructing the VM — the VM should
        // observe the existing value, not overwrite it.
        canonicalPrefs().edit()
            .putString(BranchDamKeys.THEME_MODE, ThemeMode.LIGHT.toStorageString())
            .apply()

        val vm = newViewModel()
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
    }

    @Test
    fun testSetThemeMode_UpdatesFlowAndPersists() {
        val vm = newViewModel()
        // Reset to SYSTEM so each branch starts from a known state.
        vm.setThemeMode(ThemeMode.SYSTEM)
        assertEquals("system", canonicalPrefs().getString(BranchDamKeys.THEME_MODE, null))
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)

        vm.setThemeMode(ThemeMode.DARK)
        assertEquals("dark", canonicalPrefs().getString(BranchDamKeys.THEME_MODE, null))
        assertEquals(ThemeMode.DARK, vm.themeMode.value)

        vm.setThemeMode(ThemeMode.LIGHT)
        assertEquals("light", canonicalPrefs().getString(BranchDamKeys.THEME_MODE, null))
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
    }

    @Test
    fun testSetThemeMode_DoesNotTouchOtherPrefs() {
        // The setter must write only THEME_MODE; it must not, for
        // example, clobber the sync-on-mobile-data or any other key.
        canonicalPrefs().edit()
            .putBoolean(BranchDamKeys.SYNC_ON_MOBILE_DATA, true)
            .putString(BranchDamKeys.THEME_MODE, ThemeMode.SYSTEM.toStorageString())
            .apply()

        val vm = newViewModel()
        vm.setThemeMode(ThemeMode.DARK)

        assertEquals(true, canonicalPrefs().getBoolean(BranchDamKeys.SYNC_ON_MOBILE_DATA, false))
        assertEquals("dark", canonicalPrefs().getString(BranchDamKeys.THEME_MODE, null))
    }
}
