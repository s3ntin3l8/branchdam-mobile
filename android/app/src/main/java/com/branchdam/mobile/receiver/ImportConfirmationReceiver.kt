package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.branchdam.mobile.service.ImportConfirmationNotifier

class ImportConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val itemIds = intent.getStringArrayExtra(ImportConfirmationNotifier.EXTRA_ITEM_IDS) ?: emptyArray()
        ImportConfirmationNotifier.handleAction(context, action, itemIds)
    }
}
