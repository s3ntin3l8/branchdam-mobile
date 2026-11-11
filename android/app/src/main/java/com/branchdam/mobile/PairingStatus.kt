package com.branchdam.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The server URL the app is configured with, resolved the way
 * [BranchDamApplication.readSecureEngineConfig] does: the encrypted prefs
 * first, then the plain copy. [PrefKeyMigration] moves the URL into the
 * encrypted prefs and removes it from the plain ones, so the plain prefs
 * alone are not enough.
 */
fun storedServerUrl(plain: SharedPreferences, secure: SharedPreferences?): String? =
    (secure?.getString(BranchDamApplication.KEY_SERVER_URL, null)
        ?: plain.getString(BranchDamApplication.KEY_SERVER_URL, null))
        ?.takeIf { it.isNotBlank() }

/** A server URL is stored (setup was done, whether or not the server answered). */
fun hasServerUrl(plain: SharedPreferences, secure: SharedPreferences?): Boolean =
    storedServerUrl(plain, secure) != null

/**
 * Whether this device has been paired with a server: a handshake has
 * succeeded against the URL it is configured with ([confirmPairing]). A
 * URL that never answered (mistyped, wrong key) leaves the device
 * unpaired, so the Sync and Lineage "Pair this device" prompts stay.
 * Reads only the plain prefs, where the confirmed URL is kept. Used by
 * the Sync and Lineage tabs.
 */
fun isPaired(plain: SharedPreferences): Boolean =
    !plain.getString(BranchDamKeys.PAIRED_SERVER_URL, null).isNullOrBlank()

/**
 * Records a successful handshake with the configured server. A no-op while
 * no URL is stored, so the cold-start check against the default URL on a
 * fresh install cannot confirm a pairing that was never made.
 */
fun confirmPairing(plain: SharedPreferences, secure: SharedPreferences?) {
    val url = storedServerUrl(plain, secure) ?: return
    if (plain.getString(BranchDamKeys.PAIRED_SERVER_URL, null) != url) {
        plain.edit().putString(BranchDamKeys.PAIRED_SERVER_URL, url).apply()
    }
}

/**
 * Called when [serverUrl] has just been saved as the configured server: a
 * confirmation made for a different server no longer holds, so a device
 * re-pointed at a mistyped URL is unpaired until that URL answers.
 */
fun invalidatePairingIfServerChanged(plain: SharedPreferences, serverUrl: String) {
    val confirmed = plain.getString(BranchDamKeys.PAIRED_SERVER_URL, null)
    if (confirmed != null && confirmed != serverUrl) {
        plain.edit().remove(BranchDamKeys.PAIRED_SERVER_URL).apply()
    }
}

/**
 * Once, on the first launch of the version that added
 * [BranchDamKeys.PAIRED_SERVER_URL]: an install that already has a
 * configured URL was set up before the confirmation existed and keeps
 * working, so that URL counts as confirmed (this includes an install whose
 * URL never answered; there is no way to tell those apart). On a fresh
 * install nothing is configured yet, so only the marker is written and a
 * later unanswered URL stays unpaired. Independent of the order against
 * [PrefKeyMigration] because the URL is resolved from both prefs.
 */
fun migratePairingConfirmed(plain: SharedPreferences, secure: SharedPreferences?) {
    if (plain.getBoolean(BranchDamKeys.PAIRED_CONFIRMED_MIGRATED, false)) return
    val url = storedServerUrl(plain, secure)
    plain.edit()
        .apply { if (url != null) putString(BranchDamKeys.PAIRED_SERVER_URL, url) }
        .putBoolean(BranchDamKeys.PAIRED_CONFIRMED_MIGRATED, true)
        .apply()
}

/** [isPaired] now and after every change to the confirmed server URL. */
fun pairedFlow(plain: SharedPreferences): Flow<Boolean> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BranchDamKeys.PAIRED_SERVER_URL) trySend(isPaired(plain))
    }
    plain.registerOnSharedPreferenceChangeListener(listener)
    trySend(isPaired(plain))
    awaitClose { plain.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

/** [isPaired] as Compose state, kept current while the caller is composed. */
@Composable
fun rememberIsPaired(): State<Boolean> {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val flow = remember(prefs) { pairedFlow(prefs) }
    return flow.collectAsState(initial = remember(prefs) { isPaired(prefs) })
}

/** What onboarding's pairing page shows for the outcome of a pairing attempt. */
sealed interface PairingResult {
    /** No attempt yet (or none since the screen opened). */
    data object Idle : PairingResult

    /** An attempt is in flight. */
    data object Connecting : PairingResult

    /** The handshake with [serverUrl] succeeded. */
    data class Connected(val serverUrl: String) : PairingResult

    /** The attempt failed; [message] is user-facing. */
    data class Failed(val message: String) : PairingResult
}

/**
 * Pure mapping from the connection state to the onboarding outcome.
 * [attempted] guards the cold-start check against the default server
 * URL on a fresh install, whose failure is not a pairing failure.
 */
internal fun mapPairingResult(
    attempted: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean,
    error: String?,
    serverUrl: String,
): PairingResult = when {
    !attempted -> PairingResult.Idle
    isConnecting -> PairingResult.Connecting
    isConnected -> PairingResult.Connected(serverUrl)
    error != null -> PairingResult.Failed(error)
    else -> PairingResult.Idle
}
