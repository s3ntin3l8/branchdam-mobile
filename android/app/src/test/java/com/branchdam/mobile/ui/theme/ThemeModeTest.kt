package com.branchdam.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ThemeMode] — the user-selectable color-scheme mode
 * (System / Light / Dark) and its string round-trip helpers.
 *
 * The string format matters for two reasons:
 *   1. The persisted SharedPreferences value must survive an enum
 *      reorder — `name` (string) is stable across constant reorders;
 *      `ordinal` is not. See [ThemePreferences].
 *   2. The Android key (`branchdam_theme_mode`) is documented to
 *      match the future iOS UserDefaults key, so the storage shape
 *      is part of the cross-platform contract.
 */
class ThemeModeTest {

    @Test
    fun testToStorageString_LowercasesEnumName() {
        assertEquals("system", ThemeMode.SYSTEM.toStorageString())
        assertEquals("light", ThemeMode.LIGHT.toStorageString())
        assertEquals("dark", ThemeMode.DARK.toStorageString())
    }

    @Test
    fun testToThemeModeOrNull_AcceptsLowercaseCanonicalNames() {
        assertEquals(ThemeMode.SYSTEM, "system".toThemeModeOrNull())
        assertEquals(ThemeMode.LIGHT, "light".toThemeModeOrNull())
        assertEquals(ThemeMode.DARK, "dark".toThemeModeOrNull())
    }

    @Test
    fun testToThemeModeOrNull_AcceptsUppercase() {
        // Forward-compatible with a future iOS writer that might
        // serialize the Swift enum's raw value un-lowercased.
        assertEquals(ThemeMode.SYSTEM, "SYSTEM".toThemeModeOrNull())
        assertEquals(ThemeMode.LIGHT, "LIGHT".toThemeModeOrNull())
    }

    @Test
    fun testToThemeModeOrNull_AcceptsMixedCase() {
        assertEquals(ThemeMode.DARK, "Dark".toThemeModeOrNull())
    }

    @Test
    fun testToThemeModeOrNull_RejectsUnknownString() {
        assertNull("sepia".toThemeModeOrNull())
        assertNull("".toThemeModeOrNull())
        assertNull("darkmode".toThemeModeOrNull())
    }

    @Test
    fun testRoundTripAllValues() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, mode.toStorageString().toThemeModeOrNull())
        }
    }
}
