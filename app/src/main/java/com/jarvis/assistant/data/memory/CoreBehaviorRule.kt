package com.jarvis.assistant.data.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "core_behavior_rules",
    primaryKeys = ["ownerUid", "instructionId"],
    indices = [
        Index(value = ["ownerUid", "activeStatus"]),
        Index(value = ["ownerUid", "normalizedRule"]),
    ]
)
data class CoreBehaviorRule(
    val instructionId: String,
    val instructionText: String,
    val normalizedRule: String,
    val category: String,
    val scope: String,
    val priority: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val activeStatus: Boolean = true,
    val sourceConversation: String? = null,
    val confirmationStatus: String = CONFIRMED_LOCAL,
    val ownerUid: String = LEGACY_UNASSIGNED_UID,
) {
    fun isOwnedBy(accountUid: String): Boolean =
        accountUid.isNotBlank() &&
            accountUid != LEGACY_UNASSIGNED_UID &&
            ownerUid == accountUid

    companion object {
        const val CONFIRMED_LOCAL = "confirmed_local"
        const val LEGACY_UNASSIGNED_UID = "__legacy_unassigned__"
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_TEXT = "text"
        const val SCOPE_VOICE = "voice"
        const val SCOPE_WHATSAPP = "whatsapp"
        const val SCOPE_BACKGROUND_AGENT = "background_agent"
    }
}

@Entity(
    tableName = "core_behavior_history",
    indices = [
        Index(value = ["ownerUid", "instructionId"]),
        Index(value = ["ownerUid", "occurredAt"]),
        Index(value = ["ownerUid", "eventId"], unique = true),
    ]
)
data class CoreBehaviorHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventId: String,
    val instructionId: String,
    val action: String,
    val instructionSnapshot: String,
    val surface: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val ownerUid: String = CoreBehaviorRule.LEGACY_UNASSIGNED_UID,
)

@Entity(
    tableName = "core_behavior_deletions",
    primaryKeys = ["ownerUid", "instructionId"],
)
data class CoreBehaviorDeletionEntity(
    val instructionId: String,
    val instructionSnapshot: String,
    val deletedAt: Long,
    val ownerUid: String = CoreBehaviorRule.LEGACY_UNASSIGNED_UID,
)
