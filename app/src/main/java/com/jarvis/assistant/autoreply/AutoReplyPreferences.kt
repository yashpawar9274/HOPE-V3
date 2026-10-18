package com.jarvis.assistant.autoreply

import android.content.Context
import org.json.JSONArray
import java.util.Locale

data class AutoReplyChat(
    val packageName: String,
    val conversation: String
) {
    val key: String
        get() = AutoReplyPolicy.chatKey(packageName, conversation)
}

object AutoReplyPreferences {
    private const val FILE_NAME = "jarvis_auto_reply"
    private const val KEY_ENABLED_APPS = "enabled_apps"
    private const val KEY_ALLOWED_CHATS = "allowed_chats"
    private const val KEY_BLOCKED_CHATS = "blocked_chats"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isAppEnabled(context: Context, packageName: String): Boolean =
        preferences(context).getStringSet(KEY_ENABLED_APPS, emptySet())
            ?.contains(packageName) == true

    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val prefs = preferences(context)
        val apps = prefs.getStringSet(KEY_ENABLED_APPS, emptySet()).orEmpty().toMutableSet()
        if (enabled) apps.add(packageName) else apps.remove(packageName)
        check(prefs.edit().putStringSet(KEY_ENABLED_APPS, apps).commit()) {
            "Could not save auto-reply app settings."
        }
    }

    fun allowedChats(context: Context): List<AutoReplyChat> {
        val serialized = preferences(context).getString(KEY_ALLOWED_CHATS, "[]") ?: "[]"
        return try {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("package").trim()
                    val conversation = item.optString("conversation").trim()
                    if (packageName.isNotBlank() && conversation.isNotBlank()) {
                        add(AutoReplyChat(packageName, conversation))
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not read the auto-reply chat allow-list.", e)
        }
    }

    fun isChatAllowed(context: Context, packageName: String, conversation: String): Boolean {
        val wanted = AutoReplyPolicy.chatKey(packageName, conversation)
        if (NotificationIntelligenceStore.replyToAllEnabled(context)) {
            val blocked = blockedChats(context)
            return blocked.none { AutoReplyPolicy.chatKey(it.packageName, it.conversation) == wanted }
        }
        return allowedChats(context).any { it.key == wanted }
    }

    private fun blockedChats(context: Context): List<AutoReplyChat> {
        val serialized = preferences(context).getString(KEY_BLOCKED_CHATS, "[]") ?: "[]"
        return try {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("package").trim()
                    val conversation = item.optString("conversation").trim()
                    if (packageName.isNotBlank() && conversation.isNotBlank()) {
                        add(AutoReplyChat(packageName, conversation))
                    }
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read excluded auto-reply chats.", error)
        }
    }

    fun setChatAllowed(
        context: Context,
        packageName: String,
        conversation: String,
        allowed: Boolean
    ) {
        val cleanConversation = conversation.trim()
        require(packageName.isNotBlank() && cleanConversation.isNotBlank()) {
            "A package and conversation name are required to change the allow-list."
        }
        val current = allowedChats(context).toMutableList()
        val key = AutoReplyPolicy.chatKey(packageName, cleanConversation)
        current.removeAll { it.key == key }
        if (allowed) current.add(AutoReplyChat(packageName, cleanConversation))
        val blocked = blockedChats(context).toMutableList()
        blocked.removeAll { it.key == key }
        if (!allowed && NotificationIntelligenceStore.replyToAllEnabled(context)) {
            blocked.add(AutoReplyChat(packageName, cleanConversation))
        }
        val json = JSONArray().apply {
            current.forEach { chat ->
                put(org.json.JSONObject().put("package", chat.packageName).put("conversation", chat.conversation))
            }
        }
        val blockedJson = JSONArray().apply {
            blocked.forEach { chat ->
                put(org.json.JSONObject().put("package", chat.packageName).put("conversation", chat.conversation))
            }
        }
        check(preferences(context).edit()
            .putString(KEY_ALLOWED_CHATS, json.toString())
            .putString(KEY_BLOCKED_CHATS, blockedJson.toString())
            .commit()) {
            "Could not update the auto-reply chat allow-list."
        }
    }

    fun clearAllowedChats(context: Context) {
        check(preferences(context).edit().remove(KEY_ALLOWED_CHATS).remove(KEY_BLOCKED_CHATS).commit()) {
            "Could not clear the auto-reply chat allow-list."
        }
    }
}

object AutoReplyPolicy {
    private val supportedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    fun isSupportedPackage(packageName: String, defaultSmsPackage: String?): Boolean =
        packageName in supportedPackages ||
            (!defaultSmsPackage.isNullOrBlank() && packageName == defaultSmsPackage)

    fun chatKey(packageName: String, conversation: String): String =
        "${packageName.trim().lowercase(Locale.ROOT)}:${conversation.trim().lowercase(Locale.ROOT)}"

    fun containsSensitiveContent(text: String): Boolean =
        Regex(
            """\b(otp|code|one[- ]time (?:password|code)|verification code|passcode|password|pin(?: code)?|bank(?:ing)?|upi|payment|transaction|credit card|debit card|cvv|security code)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
}
