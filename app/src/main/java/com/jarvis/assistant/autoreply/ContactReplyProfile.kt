package com.jarvis.assistant.autoreply

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

enum class ReplyProfileMode {
    DISABLED,
    SUGGEST_ONLY,
    LIMITED_AUTO_REPLY
}

enum class ReplyIdentityKind {
    APP_CONVERSATION,
    PHONE_NUMBER,
    NOTIFICATION_INSTANCE
}

@Entity(
    tableName = "contact_reply_profiles",
    indices = [Index(value = ["platformPackage", "identityKey"], unique = true)]
)
data class ContactReplyProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platformPackage: String,
    val identityKey: String,
    val identityKind: String,
    val displayName: String,
    val mode: String = ReplyProfileMode.SUGGEST_ONLY.name,
    val replyLanguage: String = "Auto",
    val tone: String = "",
    val instructions: String = "",
    val maxReplyCharacters: Int = 300,
    val userConfirmedAutoReply: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "discovered_reply_contacts")
data class DiscoveredReplyContact(
    @PrimaryKey val discoveryKey: String,
    val platformPackage: String,
    val identityKey: String,
    val identityKind: String,
    val displayName: String,
    val lastSeenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reply_event_metadata")
data class ReplyEventMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long?,
    val platformPackage: String,
    val identityKey: String,
    val eventToken: String,
    val outcome: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ReplyContactIdentity(
    val key: String,
    val kind: ReplyIdentityKind,
    val displayName: String
)

object ReplyContactIdentityResolver {
    fun resolve(
        platformPackage: String,
        shortcutId: String?,
        phoneAddress: String?,
        notificationKey: String,
        displayName: String
    ): ReplyContactIdentity {
        require(platformPackage.isNotBlank()) { "Platform package must not be blank." }
        val shortcut = shortcutId?.trim()?.takeIf(String::isNotEmpty)
        if (shortcut != null) {
            return ReplyContactIdentity(
                key = "app:${sha256("$platformPackage\u0000$shortcut")}",
                kind = ReplyIdentityKind.APP_CONVERSATION,
                displayName = displayName
            )
        }

        val phone = normalizePhoneAddress(phoneAddress)
        if (phone != null) {
            return ReplyContactIdentity(
                key = "phone:${sha256(phone)}",
                kind = ReplyIdentityKind.PHONE_NUMBER,
                displayName = displayName
            )
        }

        require(notificationKey.isNotBlank()) { "Notification key must not be blank." }
        return ReplyContactIdentity(
            key = "notification:${sha256("$platformPackage\u0000$notificationKey")}",
            kind = ReplyIdentityKind.NOTIFICATION_INSTANCE,
            displayName = displayName
        )
    }

    fun normalizePhoneAddress(address: String?): String? {
        val raw = address?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val withoutScheme = when {
            raw.startsWith("tel:", ignoreCase = true) ->
                raw.substringAfter(':').substringBefore(';')
            raw.contains(':') || raw.contains('@') -> return null
            else -> raw
        }
        if (withoutScheme.any(Char::isLetter)) return null
        val normalized = buildString {
            withoutScheme.forEachIndexed { index, char ->
                when {
                    char.isDigit() -> append(char)
                    char == '+' && index == 0 -> append(char)
                }
            }
        }
        return normalized.takeIf { it.count(Char::isDigit) >= 7 }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
