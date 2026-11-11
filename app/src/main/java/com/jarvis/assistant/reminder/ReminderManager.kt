package com.jarvis.assistant.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReminderManager(context: Context) {
    private val appContext = context.applicationContext
    private val dao = ReminderDatabase.get(appContext).reminderDao()
    private val workManager = WorkManager.getInstance(appContext)

    fun observePending() = dao.observePending()

    suspend fun listPending(): List<ReminderEntity> = dao.pending()

    suspend fun create(
        text: String,
        triggerAtMillis: Long,
        recurrence: String = "NONE",
        timeZoneId: String = ZoneId.systemDefault().id
    ): ReminderEntity {
        require(text.isNotBlank()) { "Reminder text is required." }
        require(triggerAtMillis > System.currentTimeMillis()) { "Reminder time must be in the future." }
        require(recurrence in SUPPORTED_RECURRENCES) { "Recurrence must be NONE, DAILY, or WEEKLY." }
        val zone = ZoneId.of(timeZoneId)
        val local = Instant.ofEpochMilli(triggerAtMillis).atZone(zone).toLocalDateTime()
        val entity = ReminderEntity(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            triggerAtMillis = triggerAtMillis,
            localDateTime = local.toString(),
            timeZoneId = zone.id,
            recurrence = recurrence
        )
        dao.upsert(entity)
        enqueue(entity)
        return entity
    }

    suspend fun update(reminder: ReminderEntity) {
        require(reminder.text.isNotBlank()) { "Reminder text is required." }
        require(reminder.triggerAtMillis > System.currentTimeMillis()) { "Reminder time must be in the future." }
        require(reminder.recurrence in SUPPORTED_RECURRENCES) { "Unsupported recurrence." }
        dao.upsert(reminder.copy(completed = false))
        enqueue(reminder.copy(completed = false))
    }

    suspend fun markCompleted(id: String): Boolean {
        val updated = dao.markCompleted(id) > 0
        if (updated) workManager.cancelUniqueWork(workName(id))
        return updated
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        workManager.cancelUniqueWork(workName(id))
    }

    suspend fun reschedulePending() {
        dao.pending().forEach(::enqueue)
    }

    internal fun enqueue(reminder: ReminderEntity) {
        if (reminder.completed) return
        val delayMillis = (reminder.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(Data.Builder().putString(ReminderWorker.KEY_REMINDER_ID, reminder.id).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(reminder.id), ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private val SUPPORTED_RECURRENCES = setOf("NONE", "DAILY", "WEEKLY")
        internal fun workName(id: String) = "hope_reminder_$id"
    }
}
