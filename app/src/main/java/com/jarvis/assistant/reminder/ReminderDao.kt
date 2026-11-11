package com.jarvis.assistant.reminder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM hope_reminders WHERE completed = 0 ORDER BY triggerAtMillis ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM hope_reminders WHERE completed = 0 ORDER BY triggerAtMillis ASC")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM hope_reminders WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("UPDATE hope_reminders SET completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: String): Int

    @Query("DELETE FROM hope_reminders WHERE id = :id")
    suspend fun delete(id: String)
}
