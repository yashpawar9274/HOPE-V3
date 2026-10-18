package com.jarvis.assistant.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HopeCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(HopeCallController.EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            Log.w(TAG, "Ignoring call action without a call identity.")
            return
        }
        val succeeded = when (intent.action) {
            HopeCallController.ACTION_ANSWER -> HopeCallController.answer(callId)
            HopeCallController.ACTION_REJECT -> HopeCallController.reject(callId)
            HopeCallController.ACTION_HANG_UP -> HopeCallController.hangUp(callId)
            else -> false
        }
        if (!succeeded) Log.w(TAG, "Call action could not be applied to the current Telecom call.")
    }

    private companion object {
        const val TAG = "HopeCallActionReceiver"
    }
}
