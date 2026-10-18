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

    fun isChatAllowed(context: Context, packageName: String, conversation: String): Boolean {
        return AutoReplyPolicy.isChatAllowed(
            packageName,
            conversation,
            blockedChats(context)
        )
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
            "A package and conversation name are required to change the conversation exclusion."
        }
        val blocked = blockedChats(context).toMutableList()
        val key = AutoReplyPolicy.chatKey(packageName, cleanConversation)
        blocked.removeAll { it.key == key }
        if (!allowed) {
            blocked.add(AutoReplyChat(packageName, cleanConversation))
        }
        val blockedJson = JSONArray().apply {
            blocked.forEach { chat ->
                put(org.json.JSONObject().put("package", chat.packageName).put("conversation", chat.conversation))
            }
        }
        check(preferences(context).edit()
            .putString(KEY_BLOCKED_CHATS, blockedJson.toString())
            .commit()) {
            "Could not update auto-reply conversation exclusions."
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

    fun isChatAllowed(
        packageName: String,
        conversation: String,
        excludedChats: List<AutoReplyChat>
    ): Boolean {
        val wanted = chatKey(packageName, conversation)
        return excludedChats.none { it.key == wanted }
    }

    fun notificationTextOrLatestMessage(text: String, incomingMessages: List<String>): String =
        text.trim().takeIf(String::isNotBlank)
            ?: incomingMessages.lastOrNull()?.trim().orEmpty()

    private val sensitiveContentPattern = Regex(
        """\b(?:otp|code|one[- ]time(?: password| code)?|verification|passcode|password|pin(?: code)?|bank(?:ing)?|upi|payment|transaction|credit card|debit card|cvv|security code|security alert|login|sign[- ]?in|authentication|account balance|credited|debited|aadhaar|aadhar|ssn|passport)\b|(?:ओटीपी|बैंक|खाता|लेनदेन|भुगतान|रुपये|सुरक्षा कोड)""",
        RegexOption.IGNORE_CASE
    )

    fun containsSensitiveContent(text: String): Boolean =
        sensitiveContentPattern.containsMatchIn(text)
}
