package com.branchdam.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.branchdam.mobile.R

/**
 * Remembers a launcher for the system folder picker (SAF
 * `ACTION_OPEN_DOCUMENT_TREE`) and returns a function that opens it.
 * [onPicked] receives the chosen tree URI; cancelling the picker does
 * nothing. Used both when a card reader is attached but its volume is
 * not readable directly, and from the manual "Import from SD card" entry.
 */
@Composable
fun rememberOtgCardFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(null) }
}

/**
 * Shown when a card reader was attached but the app cannot read its
 * volume directly: asks the user to choose the card in the folder picker.
 */
@Composable
fun OtgCardAccessDialog(
    deviceLabel: String,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = { Icon(Icons.Default.SdCard, contentDescription = null) },
        title = { Text(stringResource(R.string.otg_card_access_title)) },
        text = { Text(stringResource(R.string.otg_card_access_body, deviceLabel)) },
        confirmButton = {
            Button(onClick = onChooseFolder) {
                Text(stringResource(R.string.otg_card_access_choose))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.otg_card_access_not_now))
            }
        },
    )
}
