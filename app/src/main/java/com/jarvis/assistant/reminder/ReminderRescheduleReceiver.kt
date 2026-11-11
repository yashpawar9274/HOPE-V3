package com.jarvis.assistant.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<ReminderRescheduleWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "hope_reminder_reschedule",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
