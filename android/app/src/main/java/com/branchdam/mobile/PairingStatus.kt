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
 * Whether this device has been paired with a server: a server URL is
 * stored. The plain prefs hold it on every save path
 * ([ConnectionSettingsStore.save] writes it even when the Keystore is
 * unavailable), so they are the single source of truth. Read by
 * onboarding, the Sync tab, the Lineage tab and `MainActivity`'s
 * "onboarding done" default.
 */
fun isPaired(prefs: SharedPreferences): Boolean =
    !prefs.getString(BranchDamApplication.KEY_SERVER_URL, null).isNullOrBlank()

/** [isPaired] now and after every change to the stored server URL. */
fun pairedFlow(prefs: SharedPreferences): Flow<Boolean> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BranchDamApplication.KEY_SERVER_URL) trySend(isPaired(prefs))
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
