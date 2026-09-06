package com.branchdam.mobile.ui.theme

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure [resolveTheme] function extracted from
 * [BranchDamTheme]. The composable delegates all of its branching
 * here so the dark/light decision and the Material-You gate can be
 * exercised without a Compose runtime.
 *
 * Contract:
 *   - SYSTEM → follows `systemIsDark` (so OS flips propagate).
 *   - LIGHT  → always `false`, regardless of OS.
 *   - DARK   → always `true`, regardless of OS.
 *   - `useDynamic` is true iff `dynamicColor == true` AND the SDK
 *     is at or above [Build.VERSION_CODES.S] (API 31, Android 12).
 *     On older SDKs, dynamic color is unsupported and the static
 *     schemes must be used regardless of the caller's preference.
 */
class ThemeResolutionTest {

    private val api31 = Build.VERSION_CODES.S

    @Test
    fun testSystemFollowsSystemDark_True() {
        val (dark, _) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = true, dynamicColor = false, sdkInt = api31)
        assertEquals(true, dark)
    }

    @Test
    fun testSystemFollowsSystemDark_False() {
        val (dark, _) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = false, sdkInt = api31)
        assertEquals(false, dark)
    }

    @Test
    fun testLightIsAlwaysLight() {
        val (dark1, _) = resolveTheme(ThemeMode.LIGHT, systemIsDark = true, dynamicColor = false, sdkInt = api31)
        val (dark2, _) = resolveTheme(ThemeMode.LIGHT, systemIsDark = false, dynamicColor = false, sdkInt = api31)
        assertEquals(false, dark1)
        assertEquals(false, dark2)
    }

    @Test
    fun testDarkIsAlwaysDark() {
        val (dark1, _) = resolveTheme(ThemeMode.DARK, systemIsDark = true, dynamicColor = false, sdkInt = api31)
        val (dark2, _) = resolveTheme(ThemeMode.DARK, systemIsDark = false, dynamicColor = false, sdkInt = api31)
        assertEquals(true, dark1)
        assertEquals(true, dark2)
    }

    @Test
    fun testDynamicColor_OnApi31OrAbove_RequestedAndEnabled() {
        val (_, useDynamic) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = true, sdkInt = api31)
        assertEquals(true, useDynamic)
    }

    @Test
    fun testDynamicColor_OnApi31OrAbove_RequestedAndEnabled_Api33() {
        // A future-proofing check: any SDK ≥ 31 must continue to
        // honor dynamicColor=true. The implementation must not gate
        // on a specific Android version beyond the minimum.
        val (_, useDynamic) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = true, sdkInt = 33)
        assertEquals(true, useDynamic)
    }

    @Test
    fun testDynamicColor_OffWhenCallerOptedOut_EvenOnSupportedSdk() {
        val (_, useDynamic) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = false, sdkInt = api31)
        assertEquals(false, useDynamic)
    }

    @Test
    fun testDynamicColor_OffOnOlderSdk_EvenWhenCallerRequested() {
        // Android 11 (API 30) and below: dynamic color is not
        // implemented by the platform. The static schemes must be
        // returned, regardless of dynamicColor=true.
        val (_, useDynamic) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = true, sdkInt = 30)
        assertEquals(false, useDynamic)
    }

    @Test
    fun testDynamicColor_OffAtSdk30Boundary() {
        // Explicit boundary check: SDK 30 is one below the minimum,
        // so the gate must reject it. (Off-by-one regression guard.)
        val (_, useDynamic) = resolveTheme(ThemeMode.SYSTEM, systemIsDark = false, dynamicColor = true, sdkInt = 30)
        assertEquals(false, useDynamic)
    }
}
