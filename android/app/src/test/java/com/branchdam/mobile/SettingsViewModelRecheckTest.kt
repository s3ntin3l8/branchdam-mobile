package com.branchdam.mobile

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the PR #133 connection-status refresh.
 *
 * The PR closed the PR #131 review point #4 (re-check trigger after
 * server URL change in Settings) by:
 *  1. Adding [SettingsViewModel.checkConnection] that runs the
 *     `EngineHolder.testConnection` handshake and updates
 *     `isConnected` accordingly.
 *  2. Wiring a `LaunchedEffect(Unit)` in [SettingsScreen] so the
 *     check fires on every re-entry (cold start, navigation back
 *     from Sync Status, …).
 *  3. Calling `connect()` from `applyPairingConfig` so a fresh QR
 *     pair immediately tries the new server.
 *
 * Three seams (`testConnectionFn`, `reachabilityTimeoutMs`,
 * `testIoDispatcher`) plus a `withTimeoutOrNull` mirror of the
 * SyncStatusViewModel fix let a JVM test drive success / failure /
 * hang paths under a virtual-clock StandardTestDispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelRecheckTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SettingsViewModel.testIoDispatcher = testDispatcher
        // WorkManager is initialised in the SettingsViewModel branch
        // via syncOnMobileData reads — install a synchronous
        // WorkManager so the constructor doesn't crash.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Reset every test seam to its production default so
        // cross-test contamination can't leak a stub into a later
        // test class.
        SettingsViewModel.testConnectionFn = { com.branchdam.mobile.EngineHolder.testConnection() }
        SettingsViewModel.reachabilityTimeoutMs = 5_000L
        SettingsViewModel.testIoDispatcher = Dispatchers.IO
        SettingsViewModel.engineInit = { _, _, _, _, _, _ ->
            com.branchdam.mobile.EngineHolder.initialize(
                dbPath = "",
                baseURL = "",
                apiKey = "",
                agentID = "",
                version = "",
                devCleartextHosts = "",
            )
        }
    }

    @Test
    fun testCheckConnection_successSetsIsConnectedTrue() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { true }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertTrue(
            "a successful handshake must update isConnected = true",
            viewModel.isConnected.value,
        )
    }

    @Test
    fun testCheckConnection_failureSetsIsConnectedFalse() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { false }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertFalse(
            "a failed handshake must update isConnected = false",
            viewModel.isConnected.value,
        )
    }

    @Test
    fun testCheckConnection_timeoutSetsIsConnectedFalse() = runTest(testDispatcher) {
        // A misconfigured server whose TCP connect never returns
        // must be treated as unreachable after the timeout rather
        // than locking the screen UI or the EngineHolder executor
        // that backs the gomobile binding. Lower the timeout to
        // 100ms via the test seam and install a 1s `delay()` (which
        // cooperates with the test scheduler's virtual clock) so
        // the timeout fires first.
        SettingsViewModel.reachabilityTimeoutMs = 100L
        SettingsViewModel.testConnectionFn = {
            kotlinx.coroutines.delay(1_000L)
            true
        }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertFalse(
            "a handshake that exceeds reachabilityTimeoutMs must be " +
                "treated as unreachable rather than locking the UI",
            viewModel.isConnected.value,
        )
    }

    @Test
    fun testApplyPairingConfig_invokesConnect() = runTest(testDispatcher) {
        // The QR-scan path goes QR → onConfigApplied →
        // applyPairingConfig → connect. The fix threads
        // `applyPairingConfig → connect` so the user sees the
        // status pill flip to "Connecting…" / "Connected" the
        // moment they land back on Settings after scanning.
        var engineInitCalls = 0
        SettingsViewModel.engineInit = { _, _, _, _, _, _ ->
            engineInitCalls++
            true
        }
        SettingsViewModel.testConnectionFn = { true }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        val baseline = engineInitCalls

        viewModel.applyPairingConfig(
            PairingConfig(
                serverUrl = "https://branchdam.example.com",
                apiKey = "test-key",  // pragma: allowlist secret
                agentId = "test-agent",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "applyPairingConfig must invoke connect (engineInit) " +
                "(baseline=$baseline after=$engineInitCalls)",
            engineInitCalls > baseline,
        )
    }

    @Test
    fun testApplyPairingConfig_fillsUiStateFromConfig() = runTest(testDispatcher) {
        SettingsViewModel.engineInit = { _, _, _, _, _, _ -> true }
        SettingsViewModel.testConnectionFn = { true }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        viewModel.applyPairingConfig(
            PairingConfig(
                serverUrl = "https://branchdam.example.com",
                apiKey = "test-key",  // pragma: allowlist secret
                agentId = "test-agent",
            ),
        )

        assertTrue(
            "serverUrl must reflect the QR-scanned value",
            "https://branchdam.example.com" == viewModel.serverUrl.value,
        )
        assertTrue(
            "apiKey must reflect the QR-scanned value",
            "test-key" == viewModel.apiKey.value,
        )
        assertTrue(
            "agentId must reflect the QR-scanned value",
            "test-agent" == viewModel.agentId.value,
        )
    }
}
