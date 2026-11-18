package com.jarvis.assistant.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_sync_outbox",
    indices = [
        Index(value = ["jobId"], unique = true),
        Index(value = ["ownerUid", "id"])
    ]
)
data class MemorySyncJob(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val jobId: String,
    val ownerUid: String,
    val operation: String,
    val payload: String,
    val matchText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0")
    val permanentFailure: Boolean = false,
) {
    companion object {
        const val OPERATION_REMEMBER_NOTE = "remember_note"
        const val OPERATION_SAVE_TURN = "save_turn"
        const val OPERATION_FORGET_QUERY = "forget_query"
        const val OPERATION_CLEAR_ALL = "clear_all"
        const val OPERATION_CORE_BEHAVIOR = "core_behavior"
    }
}
