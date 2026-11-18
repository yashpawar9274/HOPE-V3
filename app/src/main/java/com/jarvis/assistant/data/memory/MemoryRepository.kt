package com.jarvis.assistant.data.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun findRelevantMemories(query: String, limit: Int = 6): List<MemoryEntity> =
        MemoryRetrievalPolicy.selectRelevant(query, getAllMemories(), limit)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = "",
        confidence: Float = 1.0f,
        verificationStatus: String = MemoryEntity.STATUS_USER_CONFIRMED,
        sourceType: String = MemoryEntity.SOURCE_USER_STATEMENT,
        sourceConversationId: String? = null,
        sourceTurnId: String? = null,
    ): Long {
        require(confidence in 0.0f..1.0f) { "Memory confidence must be between 0 and 1." }
        require(
            verificationStatus in setOf(
                MemoryEntity.STATUS_USER_CONFIRMED,
                MemoryEntity.STATUS_NEEDS_CONFIRMATION,
                MemoryEntity.STATUS_INFERRED,
                MemoryEntity.STATUS_SUPERSEDED,
            )
        ) { "Memory verification status is invalid." }
        val calculatedKey = key.ifBlank {
            rawText.take(30).trim()
        }
        val calculatedValue = value.ifBlank { rawText }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = calculatedValue,
            rawText = rawText,
            confidence = confidence,
            verificationStatus = verificationStatus,
            sourceType = sourceType,
            sourceConversationId = sourceConversationId,
            sourceTurnId = sourceTurnId,
        )
        return memoryDao.insertMemoryIfAbsent(entity)
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int =
        memoryDao.deleteMemoriesByKeyword(MemoryQueryPolicy.escapeLikePattern(keyword))

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
    }
}
