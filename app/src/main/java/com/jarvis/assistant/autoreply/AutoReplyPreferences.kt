package com.jarvis.assistant.autoreply

import android.content.Context
import com.jarvis.assistant.ai.PersonaEngine
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class AutoReplyChat(
    val packageName: String,
    val conversation: String
) {
    val key: String
        get() = AutoReplyPolicy.chatKey(packageName, conversation)
}

data class StoredAssistantReply(
    val text: String,
    val sentAt: Long,
    val personaId: String = PersonaEngine.HOPE
)

object AutoReplyPreferences {
    private const val FILE_NAME = "jarvis_auto_reply"
    private const val KEY_ENABLED_APPS = "enabled_apps"
    private const val KEY_BLOCKED_CHATS = "blocked_chats"
    private const val KEY_SUBMITTED_REPLIES = "submitted_reply_fingerprints"
    private const val KEY_SMART_MODE = "smart_mode_enabled"
    private const val KEY_REPLY_PERSONA = "reply_persona"
    private const val KEY_SOCIAL_INTELLIGENCE = "social_intelligence_enabled"
    private const val KEY_PROFESSIONAL_COMMUNICATION = "professional_communication_enabled"
    private const val KEY_EMOTIONAL_CONTEXT = "emotional_context_enabled"
    private const val KEY_CONVERSATION_ENDING = "conversation_ending_enabled"
    private const val KEY_AUTO_ESCALATION = "auto_escalation_enabled"
    private const val KEY_HUMAN_REVIEW = "human_review_required"
    private const val KEY_BOUNDARY_PAUSED_CHATS = "boundary_paused_chats"
    private const val KEY_ULTRON_AUTO_DEFENSE = "ultron_auto_defense_enabled"
    private const val KEY_ULTRON_DEFENSE_TIMEOUT = "ultron_defense_timeout_minutes"
    private const val KEY_ULTRON_DEFENSE_THRESHOLD = "ultron_defense_confidence_threshold"
    private const val KEY_ULTRON_DEFENSE_SESSIONS = "ultron_defense_sessions"
    private const val ASSISTANT_REPLY_HISTORY_PREFIX = "assistant_reply_history_"
    private const val SUBMITTED_REPLY_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_SUBMITTED_REPLY_FINGERPRINTS = 500
    private const val MAX_BOUNDARY_PAUSED_CHATS = 300
    private const val MAX_ASSISTANT_REPLIES_PER_CHAT = 12
    private const val ASSISTANT_REPLY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

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

    fun selectedPersonaId(context: Context): String {
        val savedId = preferences(context).getString(KEY_REPLY_PERSONA, PersonaEngine.HOPE)
        return PersonaEngine.profiles.firstOrNull { it.id.equals(savedId, ignoreCase = true) }?.id
            ?: PersonaEngine.HOPE
    }

    fun setSelectedPersona(context: Context, personaId: String) {
        val selected = PersonaEngine.profiles.firstOrNull { it.id.equals(personaId.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Select one of the available HOPE personas.")
        val store = preferences(context)
        check(store.edit().putString(KEY_REPLY_PERSONA, selected.id).commit()) {
            "Could not save the WhatsApp reply persona."
        }
        check(selectedPersonaId(context) == selected.id) {
            "The WhatsApp reply persona could not be verified after saving."
        }
        clearUltronDefenseSessions(context)
    }

    fun isUltronAutoDefenseEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ULTRON_AUTO_DEFENSE, false)

    fun setUltronAutoDefenseEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_ULTRON_AUTO_DEFENSE, enabled).commit()) {
            "Could not save ULTRON Auto-Defense settings."
        }
        if (!enabled) clearUltronDefenseSessions(context)
    }

    fun ultronDefenseTimeoutMinutes(context: Context): Int =
        preferences(context).getInt(KEY_ULTRON_DEFENSE_TIMEOUT, 15).coerceIn(1, 120)

    fun setUltronDefenseTimeoutMinutes(context: Context, minutes: Int) {
        require(minutes in 1..120) { "ULTRON defense timeout must be between 1 and 120 minutes." }
        check(preferences(context).edit().putInt(KEY_ULTRON_DEFENSE_TIMEOUT, minutes).commit()) {
            "Could not save the ULTRON defense timeout."
        }
        check(ultronDefenseTimeoutMinutes(context) == minutes) {
            "The ULTRON defense timeout could not be verified after saving."
        }
    }

    fun ultronDefenseConfidenceThreshold(context: Context): Double =
        preferences(context).getFloat(KEY_ULTRON_DEFENSE_THRESHOLD, 0.9f)
            .toDouble()
            .coerceIn(0.85, 0.95)

    fun setUltronDefenseConfidenceThreshold(context: Context, threshold: Double) {
        require(threshold in setOf(0.85, 0.9, 0.95)) {
            "Choose a supported Auto-Defense confidence threshold."
        }
        check(
            preferences(context).edit()
                .putFloat(KEY_ULTRON_DEFENSE_THRESHOLD, threshold.toFloat())
                .commit()
        ) { "Could not save ULTRON defense sensitivity." }
    }

    @Synchronized
    fun ultronDefenseSession(
        context: Context,
        chatKey: String
    ): UltronDefenseSession? {
        val hash = conversationHash(chatKey)
        return readUltronDefenseSessions(context).firstOrNull { it.conversationHash == hash }
    }

    fun ultronDefenseIdentifier(chatKey: String): String =
        conversationHash(chatKey).take(12)

    @Synchronized
    fun beginUltronDefense(
        context: Context,
        chatKey: String,
        originalPersonaId: String,
        now: Long = System.currentTimeMillis()
    ): UltronDefenseSession {
        require(chatKey.isNotBlank()) { "A conversation key is required for ULTRON defense." }
        val session = UltronDefenseSession(
            conversationHash = conversationHash(chatKey),
            originalPersonaId = PersonaEngine.profiles.firstOrNull {
                it.id.equals(originalPersonaId, ignoreCase = true)
            }?.id ?: PersonaEngine.HOPE,
            startedAt = now,
            expiresAt = now + ultronDefenseTimeoutMinutes(context) * 60_000L,
            identityIntroduced = false
        )
        val sessions = readUltronDefenseSessions(context)
            .filterNot { it.conversationHash == session.conversationHash }
            .plus(session)
            .takeLast(100)
        saveUltronDefenseSessions(context, sessions)
        return session
    }

    @Synchronized
    fun markUltronIdentityIntroduced(context: Context, chatKey: String) {
        val hash = conversationHash(chatKey)
        val sessions = readUltronDefenseSessions(context)
        val index = sessions.indexOfFirst { it.conversationHash == hash }
        if (index < 0) return
        saveUltronDefenseSessions(
            context,
            sessions.toMutableList().apply {
                this[index] = this[index].copy(identityIntroduced = true)
            }
        )
    }

    @Synchronized
    fun endUltronDefense(context: Context, chatKey: String): UltronDefenseSession? {
        val hash = conversationHash(chatKey)
        val sessions = readUltronDefenseSessions(context)
        val active = sessions.firstOrNull { it.conversationHash == hash }
        if (active != null) {
            saveUltronDefenseSessions(context, sessions.filterNot { it.conversationHash == hash })
        }
        return active
    }

    @Synchronized
    fun clearUltronDefenseSessions(context: Context) {
        check(preferences(context).edit().remove(KEY_ULTRON_DEFENSE_SESSIONS).commit()) {
            "Could not clear ULTRON defense sessions."
        }
    }

    private fun readUltronDefenseSessions(context: Context): List<UltronDefenseSession> {
        val raw = preferences(context).getString(KEY_ULTRON_DEFENSE_SESSIONS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val hash = item.optString("conversation_hash").trim()
                    val personaId = item.optString("original_persona").trim()
                    val startedAt = item.optLong("started_at", 0L)
                    val expiresAt = item.optLong("expires_at", 0L)
                    if (hash.isNotBlank() && startedAt > 0L && expiresAt > startedAt) {
                        add(
                            UltronDefenseSession(
                                conversationHash = hash,
                                originalPersonaId = PersonaEngine.profileFor(personaId).id,
                                startedAt = startedAt,
                                expiresAt = expiresAt,
                                identityIntroduced = item.optBoolean("identity_introduced", false)
                            )
                        )
                    }
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read ULTRON defense session state.", error)
        }
    }

    private fun saveUltronDefenseSessions(context: Context, sessions: List<UltronDefenseSession>) {
        val value = JSONArray().apply {
            sessions.takeLast(100).forEach { session ->
                put(
                    JSONObject()
                        .put("conversation_hash", session.conversationHash)
                        .put("original_persona", session.originalPersonaId)
                        .put("started_at", session.startedAt)
                        .put("expires_at", session.expiresAt)
                        .put("identity_introduced", session.identityIntroduced)
                )
            }
        }
        check(preferences(context).edit().putString(KEY_ULTRON_DEFENSE_SESSIONS, value.toString()).commit()) {
            "Could not save ULTRON defense sessions."
        }
    }

    private fun conversationHash(chatKey: String): String {
        require(chatKey.isNotBlank()) { "A conversation key is required for ULTRON defense." }
        return MessageDigest.getInstance("SHA-256")
            .digest(chatKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    fun socialIntelligenceEnabled(context: Context): Boolean =
        getBooleanSetting(context, KEY_SOCIAL_INTELLIGENCE, defaultValue = true)

    fun setSocialIntelligenceEnabled(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_SOCIAL_INTELLIGENCE, enabled, "social intelligence")

    fun professionalCommunicationEnabled(context: Context): Boolean =
        getBooleanSetting(context, KEY_PROFESSIONAL_COMMUNICATION, defaultValue = true)

    fun setProfessionalCommunicationEnabled(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_PROFESSIONAL_COMMUNICATION, enabled, "professional communication")

    fun emotionalContextEnabled(context: Context): Boolean =
        getBooleanSetting(context, KEY_EMOTIONAL_CONTEXT, defaultValue = true)

    fun setEmotionalContextEnabled(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_EMOTIONAL_CONTEXT, enabled, "emotional context")

    fun conversationEndingEnabled(context: Context): Boolean =
        getBooleanSetting(context, KEY_CONVERSATION_ENDING, defaultValue = true)

    fun setConversationEndingEnabled(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_CONVERSATION_ENDING, enabled, "conversation-ending detection")

    fun automaticEscalationEnabled(context: Context): Boolean =
        getBooleanSetting(context, KEY_AUTO_ESCALATION, defaultValue = true)

    fun setAutomaticEscalationEnabled(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_AUTO_ESCALATION, enabled, "automatic owner escalation")

    fun humanReviewRequired(context: Context): Boolean =
        getBooleanSetting(context, KEY_HUMAN_REVIEW, defaultValue = true)

    fun setHumanReviewRequired(context: Context, enabled: Boolean) =
        setBooleanSetting(context, KEY_HUMAN_REVIEW, enabled, "human review")

    fun isPausedAfterBoundary(context: Context, chatKey: String): Boolean =
        preferences(context).getStringSet(KEY_BOUNDARY_PAUSED_CHATS, emptySet())
            ?.contains(chatKey.take(180)) == true

    @Synchronized
    fun setPausedAfterBoundary(context: Context, chatKey: String, paused: Boolean) {
        require(chatKey.isNotBlank()) { "A conversation key is required to save its reply boundary." }
        val store = preferences(context)
        val entries = store.getStringSet(KEY_BOUNDARY_PAUSED_CHATS, emptySet())
            .orEmpty()
            .toMutableSet()
        val key = chatKey.take(180)
        if (paused) {
            entries.add(key)
            while (entries.size > MAX_BOUNDARY_PAUSED_CHATS) entries.remove(entries.first())
        } else {
            entries.remove(key)
        }
        check(store.edit().putStringSet(KEY_BOUNDARY_PAUSED_CHATS, entries).commit()) {
            "Could not save the conversation's reply boundary."
        }
        check(isPausedAfterBoundary(context, key) == paused) {
            "The conversation's reply boundary could not be verified after saving."
        }
    }

    @Synchronized
    fun clearBoundaryPauses(context: Context) {
        check(preferences(context).edit().remove(KEY_BOUNDARY_PAUSED_CHATS).commit()) {
            "Could not clear saved conversation boundaries."
        }
    }

    @Synchronized
    fun assistantReplies(context: Context, chatKey: String, now: Long = System.currentTimeMillis()): List<StoredAssistantReply> {
        val stored = readAssistantReplies(context, chatKey)
        val entries = stored
            .filter { now - it.sentAt in 0..ASSISTANT_REPLY_RETENTION_MILLIS }
            .takeLast(MAX_ASSISTANT_REPLIES_PER_CHAT)
        if (entries.size != stored.size) {
            saveAssistantReplies(context, chatKey, entries)
        }
        return entries
    }

    @Synchronized
    fun recordAssistantReply(
        context: Context,
        chatKey: String,
        text: String,
        sentAt: Long = System.currentTimeMillis(),
        personaId: String = PersonaEngine.HOPE
    ) {
        require(chatKey.isNotBlank() && text.isNotBlank() && sentAt > 0L)
        val savedPersonaId = PersonaEngine.profileFor(personaId).id
        val entries = assistantReplies(context, chatKey)
            .filterNot { it.text == text && it.sentAt == sentAt }
            .plus(StoredAssistantReply(text.take(500), sentAt, savedPersonaId))
            .takeLast(MAX_ASSISTANT_REPLIES_PER_CHAT)
        saveAssistantReplies(context, chatKey, entries)
        check(assistantReplies(context, chatKey).lastOrNull() == entries.last()) {
            "The assistant's conversation reply could not be verified after saving."
        }
    }

    @Synchronized
    fun clearAssistantReplies(context: Context) {
        val store = preferences(context)
        val editor = store.edit()
        store.all.keys.filter { it.startsWith(ASSISTANT_REPLY_HISTORY_PREFIX) }.forEach(editor::remove)
        check(editor.commit()) { "Could not clear saved assistant conversation replies." }
    }

    private fun readAssistantReplies(context: Context, chatKey: String): List<StoredAssistantReply> {
        val raw = preferences(context).getString(assistantReplyHistoryKey(chatKey), "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val text = entry.optString("text").trim()
                    val sentAt = entry.optLong("sent_at", 0L)
                    val personaId = PersonaEngine.profileFor(
                        entry.optString("persona", PersonaEngine.HOPE)
                    ).id
                    if (text.isNotBlank() && sentAt > 0L) {
                        add(StoredAssistantReply(text, sentAt, personaId))
                    }
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read the saved assistant conversation context.", error)
        }
    }

    private fun saveAssistantReplies(context: Context, chatKey: String, entries: List<StoredAssistantReply>) {
        val value = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("text", entry.text)
                        .put("sent_at", entry.sentAt)
                        .put("persona", entry.personaId)
                )
            }
        }
        check(preferences(context).edit().putString(assistantReplyHistoryKey(chatKey), value.toString()).commit()) {
            "Could not save the assistant conversation context."
        }
    }

    private fun assistantReplyHistoryKey(chatKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(chatKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        return "$ASSISTANT_REPLY_HISTORY_PREFIX$digest"
    }

    private fun getBooleanSetting(context: Context, key: String, defaultValue: Boolean): Boolean =
        preferences(context).getBoolean(key, defaultValue)

    private fun setBooleanSetting(context: Context, key: String, enabled: Boolean, label: String) {
        val store = preferences(context)
        check(store.edit().putBoolean(key, enabled).commit()) { "Could not save $label settings." }
        check(store.getBoolean(key, !enabled) == enabled) { "Could not verify saved $label settings." }
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
        """\b(?:otp|code|one[- ]time(?: password| code)?|verification|passcode|password|pin(?: code)?|bank(?:ing)?|upi|payment|transaction|credit card|debit card|cvv|security code|security alert|login|sign[- ]?in|authentication|account balance|credited|debited|aadhaar|aadhar|ssn|passport|medical(?: advice| treatment)?|diagnosis|prescription|legal(?: advice)?|lawsuit|court case|emergency|investment(?: advice)?|loan(?: application)?|insurance claim|contract(?: terms)?|financial decision|confidential|private information|send money|money transfer|accept (?:the )?(?:offer|agreement)|sign (?:the )?(?:contract|agreement))\b|(?:ओटीपी|बैंक|खाता|लेनदेन|भुगतान|रुपये|सुरक्षा कोड|अस्पताल|दवा|कानूनी सलाह|गोपनीय|निवेश)""",
        RegexOption.IGNORE_CASE
    )

    fun containsSensitiveContent(text: String): Boolean =
        sensitiveContentPattern.containsMatchIn(text)

    fun containsSensitiveContext(messages: Iterable<String>): Boolean {
        val texts = messages.toList()
        return texts.any(::containsSensitiveContent) ||
            containsSensitiveContent(texts.joinToString("\n"))
    }
}
