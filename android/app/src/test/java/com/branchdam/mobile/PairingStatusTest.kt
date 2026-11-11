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
    private lateinit var secure: android.content.SharedPreferences

    private val urlA = "https://a.example.com"
    private val urlB = "https://b.example.com"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("pairing_status_test", Context.MODE_PRIVATE)
        secure = context.getSharedPreferences("pairing_status_test_secure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        secure.edit().clear().commit()
    }

    /** What ConnectionSettingsStore.save leaves behind: the URL in both prefs. */
    private fun save(url: String) {
        secure.edit().putString(BranchDamApplication.KEY_SERVER_URL, url).commit()
        prefs.edit().putString(BranchDamApplication.KEY_SERVER_URL, url).commit()
    }

    /** The app start: PrefKeyMigration moves the URL to the encrypted prefs and strips the plain copy. */
    private fun restart() {
        PrefKeyMigration.migrate(prefs, secure)
        migratePairingConfirmed(prefs, secure)
    }

    @Test
    fun notPairedWithoutAnything() {
        assertFalse(isPaired(prefs))
    }

    @Test
    fun storedServerUrlAloneIsNotPaired() {
        // A mistyped URL that never answered must keep the not-paired prompts.
        save(urlA)
        assertTrue(hasServerUrl(prefs, secure))
        assertFalse(isPaired(prefs))
    }

    @Test
    fun storedUrlIsFoundOnceThePlainCopyIsStripped() {
        save(urlA)
        PrefKeyMigration.migrate(prefs, secure)
        assertFalse(prefs.contains(BranchDamApplication.KEY_SERVER_URL))
        assertEquals(urlA, storedServerUrl(prefs, secure))
    }

    @Test
    fun aHandshakeConfirmsThePairingAndItSurvivesRestarts() {
        save(urlA)
        confirmPairing(prefs, secure)
        assertTrue(isPaired(prefs))
        restart()
        assertTrue("still paired after the plain URL is stripped", isPaired(prefs))
        restart()
        assertTrue(isPaired(prefs))
    }

    @Test
    fun confirmingWithoutAServerUrlDoesNothing() {
        // The cold-start check against the default URL on a fresh install.
        confirmPairing(prefs, secure)
        assertFalse(isPaired(prefs))
    }

    @Test
    fun existingInstallStaysPairedThroughTheRealStartupOrder() {
        // Upgrading: the URL is saved, the first start of this version strips the plain copy.
        save(urlA)
        restart()
        assertTrue(isPaired(prefs))
        restart()
        assertTrue(isPaired(prefs))
    }

    @Test
    fun freshInstallIsNotConfirmedByAUrlSavedAfterTheMigration() {
        restart() // first launch: nothing configured yet
        save(urlB) // pairing with a URL that then fails its handshake
        restart() // next launch
        assertFalse(isPaired(prefs))
    }

    @Test
    fun repointingAtAnotherServerUnpairsUntilItAnswers() {
        save(urlA)
        confirmPairing(prefs, secure)
        save(urlB)
        invalidatePairingIfServerChanged(prefs, urlB)
        assertFalse("a mistyped replacement never answered", isPaired(prefs))
        confirmPairing(prefs, secure)
        assertTrue(isPaired(prefs))
    }

    @Test
    fun savingTheSameServerAgainKeepsThePairing() {
        save(urlA)
        confirmPairing(prefs, secure)
        invalidatePairingIfServerChanged(prefs, urlA)
        assertTrue(isPaired(prefs))
    }

    @Test
    fun pairedFlowEmitsTheCurrentValueFirst() = runTest {
        assertFalse(pairedFlow(prefs).first())
    }

    @Test
    fun pairedFlowFollowsTheConfirmation() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<Boolean>()
        val job = launch { pairedFlow(prefs).take(3).toList(seen) }
        save(urlA) // URL alone: still unpaired, no new emission
        confirmPairing(prefs, secure)
        invalidatePairingIfServerChanged(prefs, urlB)
        job.join()
        assertEquals(listOf(false, true, false), seen)
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
