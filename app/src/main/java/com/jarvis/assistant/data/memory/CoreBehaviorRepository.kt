package com.jarvis.assistant.data.memory

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class CoreBehaviorRepository(
    private val dao: CoreBehaviorDao,
    private val ownerUidProvider: () -> String?,
) {
    fun currentOwnerUid(): String =
        ownerUidProvider()?.takeIf {
            it.isNotBlank() && it != CoreBehaviorRule.LEGACY_UNASSIGNED_UID
        }
            ?: throw IllegalStateException("Sign in to access Core Behaviour instructions.")

    fun requireCurrentOwner(expectedUid: String) {
        check(currentOwnerUid() == expectedUid) {
            "The active account changed. Core Behaviour synchronization was stopped."
        }
    }

    fun observeRules() = dao.observeRules(currentOwnerUid())

    suspend fun getRules() = dao.getRules(currentOwnerUid())

    suspend fun getActiveRules() = dao.getActiveRules(currentOwnerUid())

    suspend fun findByNormalizedRule(value: String) =
        dao.findByNormalizedRule(currentOwnerUid(), value)

    suspend fun search(query: String) = dao.search(currentOwnerUid(), query.trim())

    suspend fun getHistory(limit: Int = 100) =
        dao.getHistory(currentOwnerUid(), limit.coerceIn(1, 500))

    suspend fun save(rule: CoreBehaviorRule): CoreBehaviorRule {
        val ownerUid = currentOwnerUid()
        require(rule.isOwnedBy(ownerUid)) { "Cannot save another account's Core Behaviour rule." }
        dao.insertRuleWithHistory(rule, history(rule, action = "created"), ownerUid)
        return dao.getRule(ownerUid, rule.instructionId)
            ?: error("Core behaviour rule was not found after its database transaction.")
    }

    suspend fun saveFromCloud(rule: CoreBehaviorRule): CoreBehaviorRule {
        val ownerUid = currentOwnerUid()
        require(rule.isOwnedBy(ownerUid)) { "Cannot import another account's Core Behaviour rule." }
        dao.insertRuleWithHistory(rule, history(rule, action = "synced_from_cloud"), ownerUid)
        return dao.getRule(ownerUid, rule.instructionId)
            ?: error("Synced core behaviour rule was not found after its database transaction.")
    }

    suspend fun update(rule: CoreBehaviorRule, action: String): CoreBehaviorRule {
        val ownerUid = currentOwnerUid()
        require(rule.isOwnedBy(ownerUid)) { "Cannot update another account's Core Behaviour rule." }
        dao.updateRuleWithHistory(rule, history(rule, action), ownerUid)
        return dao.getRule(ownerUid, rule.instructionId)
            ?: error("Core behaviour rule was not found after its update transaction.")
    }

    suspend fun delete(rule: CoreBehaviorRule): CoreBehaviorDeletionEntity {
        val ownerUid = currentOwnerUid()
        require(rule.isOwnedBy(ownerUid)) { "Cannot delete another account's Core Behaviour rule." }
        val deletion = CoreBehaviorDeletionEntity(
            instructionId = rule.instructionId,
            instructionSnapshot = rule.instructionText,
            deletedAt = System.currentTimeMillis(),
            ownerUid = ownerUid,
        )
        dao.deleteRuleWithHistory(
            rule.instructionId,
            history(rule, action = "deleted", occurredAt = deletion.deletedAt),
            deletion,
            ownerUid,
        )
        return deletion
    }

    suspend fun getDeletions() = dao.getDeletions(currentOwnerUid())

    suspend fun applyRemoteDeletion(
        instructionId: String,
        instructionSnapshot: String,
        deletedAt: Long,
        localRule: CoreBehaviorRule?,
    ) {
        val ownerUid = currentOwnerUid()
        require(localRule == null || localRule.isOwnedBy(ownerUid)) {
            "Cannot apply a remote deletion to another account's rule."
        }
        val snapshotRule = localRule ?: CoreBehaviorRule(
            instructionId = instructionId,
            instructionText = instructionSnapshot,
            normalizedRule = CoreBehaviorPolicy.canonicalize(instructionSnapshot),
            category = "user_specific_preferences",
            scope = CoreBehaviorRule.SCOPE_GLOBAL,
            priority = 0,
            createdAt = deletedAt,
            updatedAt = deletedAt,
            activeStatus = false,
            ownerUid = ownerUid,
        )
        dao.recordRemoteDeletion(
            instructionId,
            history(snapshotRule, action = "deleted", occurredAt = deletedAt),
            CoreBehaviorDeletionEntity(instructionId, instructionSnapshot, deletedAt, ownerUid),
            ownerUid,
        )
    }

    suspend fun recordApplication(rule: CoreBehaviorRule, surface: String) {
        val ownerUid = currentOwnerUid()
        require(rule.isOwnedBy(ownerUid)) { "Cannot record an application for another account's rule." }
        val day = Instant.now().epochSecond / 86_400L
        val eventId = UUID.nameUUIDFromBytes(
            "applied:${rule.instructionId}:$surface:$day".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        dao.insertHistoryIfAbsent(
            history(rule, action = "applied", surface = surface, eventId = eventId)
        )
    }

    private fun history(
        rule: CoreBehaviorRule,
        action: String,
        surface: String? = null,
        eventId: String = UUID.randomUUID().toString(),
        occurredAt: Long = System.currentTimeMillis(),
    ) = CoreBehaviorHistory(
        eventId = eventId,
        instructionId = rule.instructionId,
        action = action,
        instructionSnapshot = rule.instructionText,
        surface = surface,
        occurredAt = occurredAt,
        ownerUid = rule.ownerUid,
    )
}
