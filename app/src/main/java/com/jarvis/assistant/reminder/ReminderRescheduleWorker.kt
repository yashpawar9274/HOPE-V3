package com.jarvis.assistant.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ReminderRescheduleWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ReminderManager(applicationContext).reschedulePending()
        Result.success()
    } catch (error: Exception) {
        android.util.Log.e("ReminderRescheduleWorker", "Could not reschedule reminders.", error)
        Result.retry()
    }
}
