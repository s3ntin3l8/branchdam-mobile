package com.branchdam.mobile.ui.theme

/**
 * The user-selectable color-scheme mode for the app.
 *
 * - [SYSTEM] follows the OS dark-mode setting (re-evaluated live via
 *   Compose's `isSystemInDarkTheme`, so an OS dark-mode flip
 *   propagates without a relaunch).
 * - [LIGHT] forces the light scheme regardless of OS state.
 * - [DARK] forces the dark scheme regardless of OS state.
 *
 * Persisted to SharedPreferences via [ThemePreferences] using
 * [toStorageString] / [toThemeModeOrNull] so the stored shape is
 * stable across reorders of these enum constants (using `ordinal`
 * would corrupt existing values on reorder).
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Returns the canonical lowercase storage string used for the
 * SharedPreferences value and the future iOS UserDefaults parity
 * key. Lowercase matches the documented cross-platform contract in
 * [com.branchdam.mobile.BranchDamKeys.THEME_MODE].
 */
fun ThemeMode.toStorageString(): String = name.lowercase()

/**
 * Parses a stored string back to a [ThemeMode]. Case-insensitive to
 * tolerate a future iOS-side writer that might serialize the Swift
 * enum's raw value un-lowercased.
 *
 * Returns `null` for unknown or empty input — callers must fall back
 * to a default (typically [ThemeMode.SYSTEM]).
 */
fun String.toThemeModeOrNull(): ThemeMode? =
    ThemeMode.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
