package com.branchdam.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "is paired" helper and the pairing-result mapping behind onboarding, Sync and Lineage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingStatusTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("pairing_status_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun notPairedWithoutAServerUrl() {
        assertFalse(isPaired(prefs))
    }

    @Test
    fun blankServerUrlIsNotPaired() {
        prefs.edit().putString(BranchDamApplication.KEY_SERVER_URL, "  ").commit()
        assertFalse(isPaired(prefs))
    }

    private fun storeUrl() {
        prefs.edit().putString(BranchDamApplication.KEY_SERVER_URL, "https://dam.example.com").commit()
    }

    @Test
    fun storedServerUrlAloneIsNotPaired() {
        // A mistyped URL that never answered must keep the not-paired prompts.
        storeUrl()
        assertTrue(hasServerUrl(prefs))
        assertFalse(isPaired(prefs))
    }

    @Test
    fun storedServerUrlWithAHandshakeIsPaired() {
        storeUrl()
        confirmPairing(prefs)
        assertTrue(isPaired(prefs))
    }

    @Test
    fun confirmingWithoutAServerUrlDoesNothing() {
        // The cold-start check against the default URL on a fresh install.
        confirmPairing(prefs)
        assertFalse(prefs.contains(BranchDamKeys.PAIRED_CONFIRMED))
        storeUrl()
        assertFalse(isPaired(prefs))
    }

    @Test
    fun existingInstallWithAUrlStaysPairedAfterTheMigration() {
        storeUrl()
        migratePairingConfirmed(prefs)
        assertTrue(isPaired(prefs))
    }

    @Test
    fun freshInstallIsNotConfirmedByAUrlStoredAfterTheMigration() {
        migratePairingConfirmed(prefs) // first launch: nothing stored yet
        storeUrl() // pairing with a URL that then fails its handshake
        migratePairingConfirmed(prefs) // next launch
        assertFalse(isPaired(prefs))
    }

    @Test
    fun migrationDoesNotOverrideALaterConfirmation() {
        migratePairingConfirmed(prefs)
        storeUrl()
        confirmPairing(prefs)
        migratePairingConfirmed(prefs)
        assertTrue(isPaired(prefs))
    }

    @Test
    fun pairedFlowEmitsTheCurrentValueFirst() = runTest {
        assertFalse(pairedFlow(prefs).first())
    }

    @Test
    fun pairedFlowFollowsTheConfirmation() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<Boolean>()
        val job = launch { pairedFlow(prefs).take(2).toList(seen) }
        storeUrl() // URL alone: still unpaired, no new emission
        confirmPairing(prefs)
        job.join()
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun noAttemptIsIdleEvenWithAnError() {
        // The cold-start check against the default URL fails on a fresh install.
        assertEquals(
            PairingResult.Idle,
            mapPairingResult(attempted = false, isConnecting = false, isConnected = false, error = "boom", serverUrl = "u"),
        )
    }

    @Test
    fun connectingWinsOverAStaleResult() {
        assertEquals(
            PairingResult.Connecting,
            mapPairingResult(attempted = true, isConnecting = true, isConnected = true, error = "old", serverUrl = "u"),
        )
    }

    @Test
    fun handshakeSuccessIsConnectedToTheServer() {
        assertEquals(
            PairingResult.Connected("https://dam.example.com"),
            mapPairingResult(true, false, true, null, "https://dam.example.com"),
        )
    }

    @Test
    fun handshakeFailureCarriesTheMessage() {
        assertEquals(
            PairingResult.Failed("Authentication Failed (HTTP 401) — Check API Key"),
            mapPairingResult(true, false, false, "Authentication Failed (HTTP 401) — Check API Key", "u"),
        )
    }

    @Test
    fun finishedWithNeitherSuccessNorErrorIsIdle() {
        assertEquals(PairingResult.Idle, mapPairingResult(true, false, false, null, "u"))
    }
}
