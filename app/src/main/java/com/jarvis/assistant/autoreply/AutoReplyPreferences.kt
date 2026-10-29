package com.jarvis.assistant.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    private const val KEY_SUBMITTED_REPLIES = "submitted_reply_fingerprints"
    private const val KEY_SMART_MODE = "smart_mode_enabled"
    private const val SUBMITTED_REPLY_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_SUBMITTED_REPLY_FINGERPRINTS = 500

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** HOPE Smart Auto-Reply: one opt-in switch, no rule or template required. Off by default. */
    fun isSmartModeEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SMART_MODE, false)

    fun setSmartModeEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_SMART_MODE, enabled).commit()) {
            "Could not save the HOPE Smart Auto-Reply setting."
        }
    }

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

    @Synchronized
    fun wasReplySubmittedRecently(context: Context, fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        require(fingerprint.isNotBlank())
        val stored = readSubmittedReplies(context)
        val entries = stored
            .filter { now - it.second in 0..SUBMITTED_REPLY_RETENTION_MILLIS }
        if (entries.size != stored.size) {
            saveSubmittedReplies(context, entries)
        }
        return entries.any { it.first == fingerprint }
    }

    @Synchronized
    fun recordReplySubmitted(context: Context, fingerprint: String, submittedAt: Long = System.currentTimeMillis()) {
        require(fingerprint.isNotBlank())
        val entries = readSubmittedReplies(context)
            .filter { submittedAt - it.second in 0..SUBMITTED_REPLY_RETENTION_MILLIS }
            .filterNot { it.first == fingerprint }
            .plus(fingerprint to submittedAt)
            .sortedByDescending { it.second }
            .take(MAX_SUBMITTED_REPLY_FINGERPRINTS)
        saveSubmittedReplies(context, entries)
    }

    private fun readSubmittedReplies(context: Context): List<Pair<String, Long>> {
        val serialized = preferences(context).getString(KEY_SUBMITTED_REPLIES, "[]") ?: "[]"
        return try {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val fingerprint = entry.optString("fingerprint")
                    val timestamp = entry.optLong("submitted_at", 0L)
                    if (fingerprint.isNotBlank() && timestamp > 0L) add(fingerprint to timestamp)
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read auto-reply duplicate protection state.", error)
        }
    }

    private fun saveSubmittedReplies(context: Context, entries: List<Pair<String, Long>>) {
        val serialized = JSONArray().apply {
            entries.forEach { (fingerprint, timestamp) ->
                put(JSONObject().put("fingerprint", fingerprint).put("submitted_at", timestamp))
            }
        }
        check(preferences(context).edit().putString(KEY_SUBMITTED_REPLIES, serialized.toString()).commit()) {
            "Could not save auto-reply duplicate protection state."
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
        """\b(?:otp|code|one[- ]time(?: password| code)?|verification|passcode|password|pin(?: code)?|bank(?:ing)?|upi|payment|transaction|credit card|debit card|cvv|security code|security alert|login|sign[- ]?in|authentication|account balance|credited|debited|aadhaar|aadhar|ssn|passport|medical advice|diagnosis|prescription|legal advice|lawsuit|court case|emergency|investment advice|loan application|insurance claim|contract terms)\b|(?:ओटीपी|बैंक|खाता|लेनदेन|भुगतान|रुपये|सुरक्षा कोड|अस्पताल|दवा|कानूनी सलाह)""",
        RegexOption.IGNORE_CASE
    )

    fun containsSensitiveContent(text: String): Boolean =
        sensitiveContentPattern.containsMatchIn(text)
}
