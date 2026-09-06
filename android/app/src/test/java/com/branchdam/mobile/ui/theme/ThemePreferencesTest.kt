package com.branchdam.mobile.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.BranchDamKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ThemePreferences] — the SharedPreferences-backed
 * repository that owns the user's selected [ThemeMode].
 *
 * Coverage:
 *   - Default value (SYSTEM) when no key is stored.
 *   - Round-trip every enum value through setMode / mode.
 *   - Corrupt stored value falls back to SYSTEM rather than throwing.
 *   - An external write to the same key (e.g. via adb or a future
 *     iOS-imported prefs sync) propagates to the StateFlow via the
 *     OnSharedPreferenceChangeListener, so the root theme recomposes
 *     without the user opening the settings screen.
 *
 * Runs under Robolectric because [ThemePreferences] needs a real
 * [android.content.SharedPreferences] implementation; mocking
 * SharedPreferences in this project is explicitly avoided (see
 * [com.branchdam.mobile.PrefKeyMigrationTest] for the rationale —
 * `isReturnDefaultValues = true` would hide real behavior).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemePreferencesTest {

    private fun newPrefs(): ThemePreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use a fresh prefs file per test via distinct mode; Robolectric's
        // SharedPreferences are isolated by file name in-memory.
        val prefs = context.getSharedPreferences(
            "test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        return ThemePreferences(prefs)
    }

    @Test
    fun testDefaultIsSystem_WhenNoKeyStored() {
        val prefs = newPrefs()
        assertEquals(ThemeMode.SYSTEM, prefs.mode.value)
    }

    @Test
    fun testSetMode_PersistsAndIsReadable() {
        val prefs = newPrefs()
        prefs.setMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefs.mode.value)
    }

    @Test
    fun testRoundTripEveryMode() {
        for (mode in ThemeMode.entries) {
            val prefs = newPrefs()
            prefs.setMode(mode)
            assertEquals(mode, prefs.mode.value)
        }
    }

    @Test
    fun testSetMode_WritesCanonicalLowercaseString() {
        // Locks the storage format so a future migration tool can
        // pattern-match the value and so the iOS shell can use the
        // exact same spelling.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val repo = ThemePreferences(prefs)
        repo.setMode(ThemeMode.DARK)
        assertEquals("dark", prefs.getString(BranchDamKeys.THEME_MODE, null))
    }

    @Test
    fun testCorruptStoredValue_FallsBackToSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_corrupt",
            Context.MODE_PRIVATE,
        )
        // Simulate a hand-edited prefs file (or a downgrade from a
        // future version that introduced a new mode).
        prefs.edit().putString(BranchDamKeys.THEME_MODE, "sepia").apply()

        val repo = ThemePreferences(prefs)
        assertEquals(ThemeMode.SYSTEM, repo.mode.value)
    }

    @Test
    fun testExternalWrite_PropagatesThroughFlow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_external_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val repo = ThemePreferences(prefs)

        // Sanity: starting state.
        assertEquals(ThemeMode.SYSTEM, repo.mode.value)

        // External write — same key, different process / different code path.
        prefs.edit().putString(BranchDamKeys.THEME_MODE, ThemeMode.DARK.toStorageString()).apply()
        advanceUntilIdle()

        // The listener should have fired and updated the StateFlow.
        assertEquals(ThemeMode.DARK, repo.mode.first())
        assertEquals(ThemeMode.DARK, repo.mode.value)
    }

    @Test
    fun testListenerIgnoresUnrelatedKeys() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_unrelated_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val repo = ThemePreferences(prefs)

        // Pin the value to LIGHT so any spurious flow update is observable.
        repo.setMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.mode.value)

        // Write to a key the listener should NOT react to.
        prefs.edit().putString("unrelated_key", "ignored").apply()
        advanceUntilIdle()

        // The mode flow must still report LIGHT — the listener is
        // scoped to THEME_MODE only, so unrelated writes are no-ops.
        assertEquals(ThemeMode.LIGHT, repo.mode.value)
    }

    @Test
    fun testListenerHandlesMultiKeyEditBatch() = runTest {
        // Regression guard for a batch where one of the keys is the
        // listener's key and another is unrelated. The listener must
        // still propagate THEME_MODE when the write batch contains
        // unrelated keys.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_batch_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val repo = ThemePreferences(prefs)
        repo.setMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.mode.value)

        prefs.edit()
            .putString(BranchDamKeys.THEME_MODE, ThemeMode.DARK.toStorageString())
            .putString("unrelated_key", "ignored")
            .apply()
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, repo.mode.value)
        assertEquals(
            "ignored",
            prefs.getString("unrelated_key", null),
        )
    }

    @Test
    fun testListenerIsDurableAfterGC() = runTest {
        // Regression test for the bug fixed in PR #144: the
        // SharedPreferences listener must be held by a strong
        // reference, otherwise it can be garbage-collected because
        // SharedPreferences only holds it weakly.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "test_gc_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val repo = ThemePreferences(prefs)

        // Force a garbage collection. If the listener was an
        // anonymous lambda not held by a field, it would be
        // eligible for collection here.
        System.gc()
        Runtime.getRuntime().gc()
        Thread.sleep(100) // Give GC a moment

        // Now trigger a change.
        prefs.edit().putString(BranchDamKeys.THEME_MODE, ThemeMode.DARK.toStorageString()).apply()
        advanceUntilIdle()

        // If the listener was GC'd, this would still be SYSTEM.
        assertEquals("Listener should still be active after GC", ThemeMode.DARK, repo.mode.value)
    }
}
