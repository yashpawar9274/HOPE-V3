package com.jarvis.assistant.reminder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hope_reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val text: String,
    val triggerAtMillis: Long,
    val localDateTime: String,
    val timeZoneId: String,
    val recurrence: String = "NONE",
    val completed: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)
