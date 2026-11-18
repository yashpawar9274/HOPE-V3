package com.jarvis.assistant.data.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query(
        "SELECT id FROM memories WHERE category = :category " +
            "AND rawText = :rawText COLLATE NOCASE ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun findDuplicateMemoryId(category: String, rawText: String): Long?

    @Transaction
    suspend fun insertMemoryIfAbsent(memory: MemoryEntity): Long =
        findDuplicateMemoryId(memory.category, memory.rawText) ?: insertMemory(memory)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query(
        "DELETE FROM memories WHERE rawText LIKE '%' || :keyword || '%' ESCAPE '\\' " +
            "OR `key` LIKE '%' || :keyword || '%' ESCAPE '\\' " +
            "OR `value` LIKE '%' || :keyword || '%' ESCAPE '\\'"
    )
    suspend fun deleteMemoriesByKeyword(keyword: String): Int

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}
