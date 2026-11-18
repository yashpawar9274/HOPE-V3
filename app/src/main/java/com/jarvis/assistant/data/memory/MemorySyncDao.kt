package com.jarvis.assistant.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemorySyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(job: MemorySyncJob): Long

    @Query(
        "SELECT * FROM memory_sync_outbox " +
            "WHERE ownerUid = :ownerUid AND permanentFailure = 0 " +
            "AND (:cloudEnabled = 1 OR operation = 'clear_all') ORDER BY id ASC LIMIT :limit"
    )
    suspend fun pendingJobs(
        ownerUid: String,
        cloudEnabled: Boolean,
        limit: Int,
    ): List<MemorySyncJob>

    @Query("SELECT * FROM memory_sync_outbox WHERE id = :id LIMIT 1")
    suspend fun getJob(id: Long): MemorySyncJob?

    @Query("UPDATE memory_sync_outbox SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun markAttempt(id: Long)

    @Query(
        "UPDATE memory_sync_outbox " +
            "SET lastError = :error, permanentFailure = :permanentFailure WHERE id = :id"
    )
    suspend fun markFailure(id: Long, error: String, permanentFailure: Boolean)

    @Query(
        "UPDATE memory_sync_outbox SET lastError = NULL, permanentFailure = 0 " +
            "WHERE ownerUid = :ownerUid"
    )
    suspend fun resetFailures(ownerUid: String)

    @Query("DELETE FROM memory_sync_outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "DELETE FROM memory_sync_outbox " +
            "WHERE ownerUid = :ownerUid AND operation = 'remember_note' " +
            "AND matchText IS NOT NULL AND instr(lower(matchText), lower(:query)) > 0"
    )
    suspend fun cancelPendingNotesMatching(ownerUid: String, query: String)

    @Query(
        "DELETE FROM memory_sync_outbox WHERE ownerUid = :ownerUid " +
            "AND operation = 'core_behavior' AND matchText = :instructionId"
    )
    suspend fun cancelPendingCoreBehavior(ownerUid: String, instructionId: String)

    @Query(
        "DELETE FROM memory_sync_outbox " +
            "WHERE ownerUid = :ownerUid AND operation IN ('remember_note', 'save_turn')"
    )
    suspend fun cancelPendingUploads(ownerUid: String)

    @Query("SELECT COUNT(*) FROM memory_sync_outbox WHERE ownerUid = :ownerUid")
    suspend fun pendingCount(ownerUid: String): Int

    @Query(
        "SELECT COUNT(*) FROM memory_sync_outbox " +
            "WHERE ownerUid = :ownerUid AND lastError IS NOT NULL"
    )
    suspend fun failedCount(ownerUid: String): Int

    @Query(
        "SELECT lastError FROM memory_sync_outbox " +
            "WHERE ownerUid = :ownerUid AND lastError IS NOT NULL ORDER BY id DESC LIMIT 1"
    )
    suspend fun latestError(ownerUid: String): String?
}
