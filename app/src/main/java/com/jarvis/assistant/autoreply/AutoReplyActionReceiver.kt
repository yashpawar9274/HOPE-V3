package com.jarvis.assistant.autoreply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class AutoReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        val allowed = intent.getBooleanExtra(EXTRA_ALLOWED, false)
        if (packageName.isBlank() || conversation.isBlank()) {
            Log.w(TAG, "Ignoring auto-reply action with missing chat identity")
            return
        }
        try {
            AutoReplyPreferences.setChatAllowed(context, packageName, conversation, allowed)
            val message = if (allowed) {
                "Auto-replies enabled for $conversation"
            } else {
                "Auto-replies excluded for $conversation"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            AutoReplyCoordinator.dismissChatNotification(context, packageName, conversation)
        } catch (e: Exception) {
            Log.e(TAG, "Could not update auto-reply conversation exclusions", e)
            Toast.makeText(context, "Could not update auto-reply settings.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "AutoReplyAction"
        const val EXTRA_PACKAGE = "auto_reply_package"
        const val EXTRA_CONVERSATION = "auto_reply_conversation"
        const val EXTRA_ALLOWED = "auto_reply_allowed"
    }
}
