package com.branchdam.mobile.ui.theme

import android.content.SharedPreferences
import com.branchdam.mobile.BranchDamKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StateFlow-backed wrapper over the persisted [ThemeMode].
 *
 * Each consumer ([com.branchdam.mobile.MainActivity],
 * [com.branchdam.mobile.ui.settings.SettingsViewModel]) constructs
 * its own instance from the canonical non-sensitive
 * SharedPreferences file ([BranchDamKeys.PREFS_NAME]). All
 * instances register listeners on the same backing file, so a
 * write from any consumer propagates to every instance's
 * `StateFlow` via [SharedPreferences.OnSharedPreferenceChangeListener]
 * — no centralized singleton is required.
 *
 * Constructed directly from a [SharedPreferences] instance so
 * tests can pass an isolated file without depending on
 * [com.branchdam.mobile.BranchDamApplication] or any production
 * Application subclass.
 */
class ThemePreferences(private val prefs: SharedPreferences) {

    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    init {
        // Re-read whenever THEME_MODE changes — covers writes from
        // any consumer (the setter calls edit().apply()), from
        // another process (adb shell prefs), or from a future
        // import-tool that syncs the iOS UserDefaults value into
        // the Android prefs file. Filtering on key avoids spurious
        // emissions for unrelated preference writes; the `clear()`
        // callback arrives with key=null and is filtered out as well.
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == BranchDamKeys.THEME_MODE) {
                _mode.value = readMode()
            }
        }
    }

    /**
     * Persists [mode] to SharedPreferences and updates the in-memory
     * flow via the registered listener. Safe to call from any
     * thread; [SharedPreferences.Editor.apply] writes off-thread.
     */
    fun setMode(mode: ThemeMode) {
        prefs.edit()
            .putString(BranchDamKeys.THEME_MODE, mode.toStorageString())
            .apply()
    }

    private fun readMode(): ThemeMode =
        prefs.getString(BranchDamKeys.THEME_MODE, null)
            ?.toThemeModeOrNull() ?: ThemeMode.SYSTEM
}
