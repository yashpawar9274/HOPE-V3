package com.jarvis.assistant.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_REMINDER_ID) ?: return Result.failure()
        val dao = ReminderDatabase.get(applicationContext).reminderDao()
        val reminder = dao.get(id) ?: return Result.success()
        if (reminder.completed) return Result.success()

        if (reminder.triggerAtMillis > System.currentTimeMillis()) {
            ReminderManager(applicationContext).enqueue(reminder)
            return Result.success()
        }

        val notificationPosted = postNotification(reminder)
        if (reminder.recurrence == "NONE") {
            if (notificationPosted) dao.markCompleted(id)
        } else {
            val zone = runCatching { ZoneId.of(reminder.timeZoneId) }
                .getOrElse { ZoneId.systemDefault() }
            val localTime = runCatching { LocalDateTime.parse(reminder.localDateTime) }
                .getOrElse { return Result.failure() }
            val next = com.jarvis.assistant.time.TimeCommandParser.nextOccurrence(
                localTime,
                reminder.recurrence,
                Instant.now(),
                zone
            )
                ?: return Result.failure()
            val updated = reminder.copy(
                triggerAtMillis = next.toInstant().toEpochMilli(),
                timeZoneId = zone.id
            )
            dao.upsert(updated)
            ReminderManager(applicationContext).enqueue(updated)
        }
        return Result.success()
    }

    private fun postNotification(reminder: ReminderEntity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Reminder notification was not posted because notification permission is not granted.")
            return false
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            Log.w(TAG, "Reminder notification was not posted because notifications are disabled for HOPE.")
            return false
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HOPE Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.jarvis.assistant.R.drawable.ic_jarvis_logo)
            .setContentTitle("HOPE Reminder")
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(reminder.id.hashCode(), notification)
        return true
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val ACTION_RESCHEDULE = "com.jarvis.assistant.REMINDERS_RESCHEDULE"
        private const val CHANNEL_ID = "hope_reminders"
        private const val TAG = "ReminderWorker"
    }
}
