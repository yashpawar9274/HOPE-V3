package com.jarvis.assistant.meeting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when the chosen Meeting Mode time is over and switches the mode off. */
class MeetingModeEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // isActive() deactivates automatically once the end time has passed.
        if (!MeetingModePreferences.isActive(context)) {
            MeetingNotifications.post(context, "Meeting Mode ended", "HOPE is back to normal call handling.")
        }
    }
}
