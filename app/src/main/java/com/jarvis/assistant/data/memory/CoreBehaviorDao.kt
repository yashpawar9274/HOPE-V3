package com.jarvis.assistant.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreBehaviorDao {
    @Query("SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid ORDER BY updatedAt DESC")
    fun observeRules(ownerUid: String): Flow<List<CoreBehaviorRule>>

    @Query("SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid ORDER BY updatedAt DESC")
    suspend fun getRules(ownerUid: String): List<CoreBehaviorRule>

    @Query("SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid AND activeStatus = 1 ORDER BY priority DESC, createdAt ASC")
    suspend fun getActiveRules(ownerUid: String): List<CoreBehaviorRule>

    @Query("SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid AND instructionId = :instructionId LIMIT 1")
    suspend fun getRule(ownerUid: String, instructionId: String): CoreBehaviorRule?

    @Query("SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid AND normalizedRule = :normalizedRule COLLATE NOCASE LIMIT 1")
    suspend fun findByNormalizedRule(ownerUid: String, normalizedRule: String): CoreBehaviorRule?

    @Query(
        "SELECT * FROM core_behavior_rules WHERE ownerUid = :ownerUid AND " +
            "(instructionText LIKE '%' || :query || '%' OR normalizedRule LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC"
    )
    suspend fun search(ownerUid: String, query: String): List<CoreBehaviorRule>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: CoreBehaviorRule)

    @Query(
        """
        UPDATE core_behavior_rules SET
            instructionText = :instructionText,
            normalizedRule = :normalizedRule,
            category = :category,
            scope = :scope,
            priority = :priority,
            createdAt = :createdAt,
            updatedAt = :updatedAt,
            activeStatus = :activeStatus,
            sourceConversation = :sourceConversation,
            confirmationStatus = :confirmationStatus
        WHERE ownerUid = :ownerUid AND instructionId = :instructionId
        """
    )
    suspend fun updateRuleForOwner(
        ownerUid: String,
        instructionId: String,
        instructionText: String,
        normalizedRule: String,
        category: String,
        scope: String,
        priority: Int,
        createdAt: Long,
        updatedAt: Long,
        activeStatus: Boolean,
        sourceConversation: String?,
        confirmationStatus: String,
    ): Int

    @Query("DELETE FROM core_behavior_rules WHERE ownerUid = :ownerUid AND instructionId = :instructionId")
    suspend fun deleteRule(ownerUid: String, instructionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entry: CoreBehaviorHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryIfAbsent(entry: CoreBehaviorHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletion(deletion: CoreBehaviorDeletionEntity)

    @Query("SELECT * FROM core_behavior_history WHERE ownerUid = :ownerUid ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getHistory(ownerUid: String, limit: Int): List<CoreBehaviorHistory>

    @Query("SELECT * FROM core_behavior_deletions WHERE ownerUid = :ownerUid ORDER BY deletedAt DESC")
    suspend fun getDeletions(ownerUid: String): List<CoreBehaviorDeletionEntity>

    @Transaction
    suspend fun insertRuleWithHistory(rule: CoreBehaviorRule, entry: CoreBehaviorHistory, ownerUid: String) {
        require(rule.isOwnedBy(ownerUid) && entry.ownerUid == ownerUid) {
            "Core behaviour records must belong to the active account."
        }
        insertRule(rule)
        insertHistory(entry)
    }

    @Transaction
    suspend fun updateRuleWithHistory(rule: CoreBehaviorRule, entry: CoreBehaviorHistory, ownerUid: String) {
        require(rule.isOwnedBy(ownerUid) && entry.ownerUid == ownerUid) {
            "Core behaviour records must belong to the active account."
        }
        check(updateRuleForOwner(
            ownerUid = ownerUid,
            instructionId = rule.instructionId,
            instructionText = rule.instructionText,
            normalizedRule = rule.normalizedRule,
            category = rule.category,
            scope = rule.scope,
            priority = rule.priority,
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt,
            activeStatus = rule.activeStatus,
            sourceConversation = rule.sourceConversation,
            confirmationStatus = rule.confirmationStatus,
        ) == 1) { "Core behaviour rule was not found for the active account." }
        insertHistory(entry)
    }

    @Transaction
    suspend fun deleteRuleWithHistory(
        instructionId: String,
        entry: CoreBehaviorHistory,
        deletion: CoreBehaviorDeletionEntity,
        ownerUid: String,
    ) {
        require(entry.ownerUid == ownerUid && deletion.ownerUid == ownerUid) {
            "Core behaviour records must belong to the active account."
        }
        insertHistory(entry)
        insertDeletion(deletion)
        check(deleteRule(ownerUid, instructionId) == 1) {
            "Core behaviour rule was not found for the active account."
        }
    }

    @Transaction
    suspend fun recordRemoteDeletion(
        instructionId: String,
        entry: CoreBehaviorHistory,
        deletion: CoreBehaviorDeletionEntity,
        ownerUid: String,
    ) {
        require(entry.ownerUid == ownerUid && deletion.ownerUid == ownerUid) {
            "Core behaviour records must belong to the active account."
        }
        insertHistoryIfAbsent(entry)
        insertDeletion(deletion)
        deleteRule(ownerUid, instructionId)
    }
}
