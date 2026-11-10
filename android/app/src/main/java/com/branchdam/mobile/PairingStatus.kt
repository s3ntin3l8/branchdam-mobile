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

/** A server URL is stored (setup was done, whether or not the server answered). */
fun hasServerUrl(prefs: SharedPreferences): Boolean =
    !prefs.getString(BranchDamApplication.KEY_SERVER_URL, null).isNullOrBlank()

/**
 * Whether this device has been paired with a server: a server URL is
 * stored and a handshake with it has succeeded ([confirmPairing]). A URL
 * that never answered (mistyped, wrong key) leaves the device unpaired, so
 * the Sync and Lineage "Pair this device" prompts stay. The plain prefs
 * hold the URL on every save path ([ConnectionSettingsStore.save] writes
 * it even when the Keystore is unavailable), so they are the single source
 * of truth. Read by onboarding, the Sync tab and the Lineage tab.
 */
fun isPaired(prefs: SharedPreferences): Boolean =
    hasServerUrl(prefs) && prefs.getBoolean(BranchDamKeys.PAIRED_CONFIRMED, false)

/**
 * Records a successful handshake with the stored server. A no-op while no
 * URL is stored, so the cold-start check against the default URL on a
 * fresh install cannot confirm a pairing that was never made.
 */
fun confirmPairing(prefs: SharedPreferences) {
    if (hasServerUrl(prefs) && !prefs.getBoolean(BranchDamKeys.PAIRED_CONFIRMED, false)) {
        prefs.edit().putBoolean(BranchDamKeys.PAIRED_CONFIRMED, true).apply()
    }
}

/**
 * Once, on the first launch of the version that added [BranchDamKeys.PAIRED_CONFIRMED]:
 * an install that already has a stored URL was set up before the flag
 * existed and keeps working, so it counts as confirmed. On a fresh install
 * no URL is stored yet, so nothing is confirmed and the marker just stops
 * the migration from confirming a later, unanswered URL.
 */
fun migratePairingConfirmed(prefs: SharedPreferences) {
    if (prefs.getBoolean(BranchDamKeys.PAIRED_CONFIRMED_MIGRATED, false)) return
    prefs.edit()
        .apply { if (hasServerUrl(prefs)) putBoolean(BranchDamKeys.PAIRED_CONFIRMED, true) }
        .putBoolean(BranchDamKeys.PAIRED_CONFIRMED_MIGRATED, true)
        .commit()
}

/** [isPaired] now and after every change to the stored server URL or the confirmation. */
fun pairedFlow(prefs: SharedPreferences): Flow<Boolean> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BranchDamApplication.KEY_SERVER_URL || key == BranchDamKeys.PAIRED_CONFIRMED) {
            trySend(isPaired(prefs))
        }
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    trySend(isPaired(prefs))
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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
