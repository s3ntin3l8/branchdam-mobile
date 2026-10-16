package com.branchdam.mobile.ui.components

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.branchdam.mobile.triage.PendingDeleteConsent

/**
 * Launches the system "allow branchDAM to delete these items?" dialog
 * whenever a ViewModel exposes a [PendingDeleteConsent], and reports the
 * answer back. Launches each request at most once: [onLaunched] claims it
 * (returning false if it was already claimed) so a recomposition, a second
 * screen observing the same ViewModel, or a recreated Activity (whose
 * result is still delivered to this re-registered launcher) does not show
 * it twice.
 */
@Composable
fun MediaDeleteConsentEffect(
    pending: PendingDeleteConsent?,
    onLaunched: (id: Long) -> Boolean,
    onResult: (approved: Boolean) -> Unit,
) {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        currentOnResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(pending?.id) {
        val request = pending ?: return@LaunchedEffect
        if (request.launched || !onLaunched(request.id)) return@LaunchedEffect
        try {
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            // ActivityNotFoundException / a cancelled PendingIntent: treat
            // as declined so the caller rolls back instead of waiting.
            Log.w("MediaDeleteConsent", "could not launch delete confirmation", e)
            currentOnResult(false)
        }
    }
}
