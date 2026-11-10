package com.branchdam.mobile

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What onboarding's pairing page sees while [SettingsViewModel] connects (#275). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelPairingResultTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private val config = PairingConfig(
        serverUrl = "https://branchdam.example.com",
        apiKey = "test-key", // pragma: allowlist secret
        agentId = "test-agent",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SettingsViewModel.testIoDispatcher = testDispatcher
        SettingsViewModel.securePrefsProvider = {
            it.getSharedPreferences("test_secure_prefs", android.content.Context.MODE_PRIVATE)
        }
        SettingsViewModel.engineInit = { _, _, _, _, _, _ -> true }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SettingsViewModel.testConnectionFn = { SettingsViewModel.testConnectionDetailedFn() == null }
        SettingsViewModel.testConnectionDetailedFn = { EngineHolder.testConnectionDetailed() }
        SettingsViewModel.reachabilityTimeoutMs = 5_000L
        SettingsViewModel.repairDeadlineFn = { EngineHolder.repairDeadlineUnix() }
        SettingsViewModel.testIoDispatcher = Dispatchers.IO
        SettingsViewModel.securePrefsProvider = { EncryptedPrefs.get(it) }
        SettingsViewModel.engineInit = { dbPath, baseURL, apiKey, agentID, version, devCleartextHosts ->
            EngineHolder.initialize(
                dbPath = dbPath,
                baseURL = baseURL,
                apiKey = apiKey,
                agentID = agentID,
                version = version,
                devCleartextHosts = devCleartextHosts,
            )
        }
    }

    @Test
    fun coldStartFailureIsNotAPairingFailure() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { false }
        SettingsViewModel.testConnectionDetailedFn = { "connection refused" }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertEquals(PairingResult.Idle, viewModel.pairingResult.value)
    }

    @Test
    fun successfulHandshakeIsConnectedToTheScannedServer() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { true }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.applyPairingConfig(config)
        advanceUntilIdle()

        assertEquals(PairingResult.Connected(config.serverUrl), viewModel.pairingResult.value)
        assertTrue("a successful handshake confirms the pairing", isPaired(appPrefs()))
    }

    @Test
    fun failedHandshakeLeavesTheDeviceUnpaired() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { false }
        SettingsViewModel.testConnectionDetailedFn = { "connection refused" }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.applyPairingConfig(config)
        advanceUntilIdle()

        val prefs = appPrefs()
        assertTrue("the URL is saved", hasServerUrl(prefs))
        assertFalse("but never answered, so not paired", isPaired(prefs))
    }

    @Test
    fun aLaterSuccessfulCheckPairsTheDevice() = runTest(testDispatcher) {
        SettingsViewModel.testConnectionFn = { false }
        SettingsViewModel.testConnectionDetailedFn = { "connection refused" }
        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.applyPairingConfig(config)
        advanceUntilIdle()
        assertFalse(isPaired(appPrefs()))

        SettingsViewModel.testConnectionFn = { true }
        viewModel.checkConnection()
        advanceUntilIdle()

        assertTrue(isPaired(appPrefs()))
    }

    private fun appPrefs() = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences(BranchDamKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    @Test
    fun failedHandshakeIsConnectingUntilTheErrorIsRecorded() = runTest(testDispatcher) {
        // The error probe runs after the reachability probe fails; the page
        // must keep its spinner (not fall back to Idle) until it is done.
        SettingsViewModel.testConnectionFn = { false }
        SettingsViewModel.testConnectionDetailedFn = {
            delay(2_000L)
            "HTTP 401"
        }

        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.applyPairingConfig(config)
        runCurrent()
        advanceTimeBy(1_000L)
        assertEquals(PairingResult.Connecting, viewModel.pairingResult.value)

        advanceUntilIdle()
        val result = viewModel.pairingResult.value
        assertTrue("expected Failed, was $result", result is PairingResult.Failed)
        assertEquals("Authentication Failed (HTTP 401) — Check API Key", (result as PairingResult.Failed).message)
    }

    @Test
    fun rejectedUrlIsAFailedAttempt() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        viewModel.applyPairingConfig(config.copy(serverUrl = "http://example.com"))
        advanceUntilIdle()

        assertTrue(viewModel.pairingResult.value is PairingResult.Failed)
    }
}
