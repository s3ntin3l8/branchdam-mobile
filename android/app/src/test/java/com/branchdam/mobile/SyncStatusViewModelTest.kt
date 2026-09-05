package com.branchdam.mobile

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.branchdam.mobile.ui.sync.SyncStatusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/**
 * Regression coverage for the PR #131 server reachability check.
 *
 * The original PR shipped `checkConnection()` without a timeout, so a
 * misconfigured server's TCP timeout (~75s) would lock the Sync screen
 * UI and (more importantly) the single-threaded `EngineHolder`
 * executor that backs every other gomobile binding, stalling
 * `syncBatch` for the duration. The fix wraps the handshake in
 * `withTimeoutOrNull(reachabilityTimeoutMs)`.
 *
 * The test seam (`SyncStatusViewModel.testConnectionFn`) lets us
 * drive success / failure / hang paths without loading the AAR.
 * The IO-dispatcher seam (`SyncStatusViewModel.ioDispatcher`) lets
 * the timeout test advance on the virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStatusViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Pin the production code's `withContext(ioDispatcher)` to the
        // test scheduler so the timeout and the busy-wait both
        // advance on the virtual clock. Without this, the real
        // `Dispatchers.IO` would not be observable by
        // `advanceUntilIdle` and the test would hang.
        SyncStatusViewModel.ioDispatcher = testDispatcher
        // WorkManager.getInstance() in the ViewModel constructor
        // needs a real Configuration. WorkManagerTestInitHelper
        // installs a synchronous in-memory one for the test
        // Application.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Reset the static test seams to their production defaults so
        // cross-test contamination can't leak a stub into a later
        // test class that forgets to set its own.
        SyncStatusViewModel.testConnectionFn = { com.branchdam.mobile.EngineHolder.testConnection() }
        SyncStatusViewModel.ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        SyncStatusViewModel.reachabilityTimeoutMs = 5_000L
    }

    @Test
    fun testCheckConnection_successSetsIsServerReachableTrue() = runTest(testDispatcher) {
        SyncStatusViewModel.testConnectionFn = { true }

        val viewModel = SyncStatusViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertTrue(
            "successful handshake must set isServerReachable = true",
            viewModel.uiState.value.isServerReachable,
        )
    }

    @Test
    fun testCheckConnection_failureSetsIsServerReachableFalse() = runTest(testDispatcher) {
        SyncStatusViewModel.testConnectionFn = { false }

        val viewModel = SyncStatusViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertFalse(
            "failed handshake must set isServerReachable = false",
            viewModel.uiState.value.isServerReachable,
        )
    }

    @Test
    fun testCheckConnection_timeoutSetsIsServerReachableFalse() = runTest(testDispatcher) {
        // Synthetic hang: simulates a misconfigured server whose TCP
        // connect never returns. The withTimeoutOrNull wrapper in
        // checkConnection must turn this into isServerReachable = false
        // rather than locking the viewModelScope. We lower the
        // production 5s timeout to 100ms via the test seam and
        // install a 1s `delay()` so the timeout fires first. `delay`
        // (not busy-wait) is the right primitive here because
        // `withTimeoutOrNull` cooperates with the coroutine
        // scheduler; the test scheduler auto-advances virtual time
        // for `delay` and fires the timeout on the virtual clock.
        SyncStatusViewModel.reachabilityTimeoutMs = 100L
        SyncStatusViewModel.testConnectionFn = {
            kotlinx.coroutines.delay(1_000L)
            true
        }

        val viewModel = SyncStatusViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()

        assertFalse(
            "a handshake that exceeds the timeout must be treated as " +
                "unreachable rather than locking the UI",
            viewModel.uiState.value.isServerReachable,
        )
    }

    @Test
    fun testRefresh_rerunsCheckConnection() = runTest(testDispatcher) {
        var callCount = 0
        SyncStatusViewModel.testConnectionFn = {
            callCount++
            true
        }

        val viewModel = SyncStatusViewModel(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        val initialCalls = callCount

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(
            "refresh() must re-run checkConnection at least once " +
                "(initial=$initialCalls after=$callCount)",
            callCount > initialCalls,
        )
        assertTrue(
            "successful refresh must leave isServerReachable = true",
            viewModel.uiState.value.isServerReachable,
        )
    }
}
