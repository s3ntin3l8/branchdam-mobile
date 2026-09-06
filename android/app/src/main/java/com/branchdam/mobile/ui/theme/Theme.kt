package com.branchdam.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    tertiary = Color(0xFFF57C00),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
)

/**
 * Resolves the user-selected [themeMode] into the concrete dark/light
 * boolean and the dynamic-color gate. Extracted from the composable
 * so it can be unit-tested without a Compose runtime.
 *
 * @param themeMode the persisted user preference.
 * @param systemIsDark whether the OS currently reports dark mode.
 * @param dynamicColor whether the caller wants Material You colors
 *        (only honored on API 31+).
 * @param sdkInt the build's reported SDK level; pass
 *        `Build.VERSION.SDK_INT` in production.
 * @return `(darkTheme, useDynamic)` where `darkTheme` selects the
 *         color scheme and `useDynamic` controls whether the
 *         platform-derived dynamic scheme is used.
 */
internal fun resolveTheme(
    themeMode: ThemeMode,
    systemIsDark: Boolean,
    dynamicColor: Boolean,
    sdkInt: Int,
): Pair<Boolean, Boolean> {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val useDynamic = dynamicColor && sdkInt >= Build.VERSION_CODES.S
    return dark to useDynamic
}

@Composable
fun BranchDamTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val (darkTheme, useDynamic) = resolveTheme(themeMode, systemDark, dynamicColor, Build.VERSION.SDK_INT)
    val colorScheme = when {
        useDynamic -> if (darkTheme) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
