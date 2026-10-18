package com.jarvis.assistant.autoreply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

enum class NotificationRuleAction {
    IGNORE,
    ANNOUNCE,
    DISPLAY,
    SUGGEST_REPLY,
    AUTHORIZED_AUTO_REPLY
}

data class NotificationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageName: String = "*",
    val keyword: String = "",
    val sender: String = "",
    val action: NotificationRuleAction,
    val template: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0
)

data class NotificationRecord(
    val id: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val receivedAt: Long,
    val sensitive: Boolean,
    val notificationKey: String = id,
    val isRead: Boolean = false,
    val dismissed: Boolean = false
) {
    val formattedTime: String
        get() = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(receivedAt))
}

data class NotificationRuleInput(
    val packageName: String,
    val sender: String,
    val message: String
)

object NotificationInboxEvents {
    const val ACTION_CHANGED = "com.jarvis.assistant.autoreply.NOTIFICATION_INBOX_CHANGED"
}

object NotificationRuleEngine {
    fun authorizedAutoReplyRule(rules: List<NotificationRule>): NotificationRule? =
        rules.firstOrNull { it.action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY }

    fun matchingRules(input: NotificationRuleInput, rules: List<NotificationRule>): List<NotificationRule> =
        rules.asSequence()
            .filter(NotificationRule::enabled)
            .filter { it.packageName == "*" || it.packageName.equals(input.packageName, ignoreCase = true) }
            .filter { it.sender.isBlank() || input.sender.contains(it.sender, ignoreCase = true) }
            .filter { it.keyword.isBlank() || input.message.contains(it.keyword, ignoreCase = true) }
            .sortedWith(compareByDescending<NotificationRule> { it.priority }.thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()

    fun isQuietAt(startMinute: Int?, endMinute: Int?, nowMinute: Int): Boolean {
        require(nowMinute in 0..1439) { "Current time must be between 00:00 and 23:59." }
        if (startMinute == null || endMinute == null ||
            startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute
        ) return false
        return if (startMinute < endMinute) {
            nowMinute in startMinute until endMinute
        } else {
            nowMinute >= startMinute || nowMinute < endMinute
        }
    }

    fun renderTemplate(
        template: String,
        appName: String,
        sender: String,
        message: String,
        time: String,
        suggestedReply: String = ""
    ): String = template
        .replace("{app_name}", appName, ignoreCase = true)
        .replace("{sender}", sender, ignoreCase = true)
        .replace("{message}", message, ignoreCase = true)
        .replace("{time}", time, ignoreCase = true)
        .replace("{suggested_reply}", suggestedReply, ignoreCase = true)
        .take(1000)
}

object NotificationInboxPolicy {
    fun upsert(existing: NotificationRecord?, incoming: NotificationRecord): NotificationRecord =
        if (existing == null) {
            incoming
        } else {
            incoming.copy(
                isRead = existing.isRead &&
                    existing.sender == incoming.sender && existing.message == incoming.message,
                dismissed = false
            )
        }

    fun upsertHistory(
        records: List<NotificationRecord>,
        incoming: NotificationRecord,
        maxEntries: Int
    ): List<NotificationRecord> {
        require(maxEntries > 0) { "Notification history must retain at least one entry." }
        val existing = records.firstOrNull { it.notificationKey == incoming.notificationKey }
        return (listOf(upsert(existing, incoming)) +
            records.filterNot { it.notificationKey == incoming.notificationKey })
            .take(maxEntries)
    }
}

object NotificationIntelligenceStore {
    private const val PREFS = "hope_notification_intelligence"
    private const val KEY_MASTER = "master_enabled"
    private const val KEY_READING = "reading_enabled"
    private const val KEY_ANNOUNCEMENTS = "announcements_enabled"
    private const val KEY_SENSITIVE_ANNOUNCEMENTS = "sensitive_announcements"
    private const val KEY_PAUSED = "notifications_paused"
    private const val KEY_READING_APPS = "reading_apps"
    private const val KEY_EXCLUDED_CONTACTS = "excluded_contacts"
    private const val KEY_EXCLUDED_KEYWORDS = "excluded_keywords"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"
    private const val KEY_RULES = "notification_rules"
    private const val KEY_HISTORY = "notification_history"
    private const val KEY_PROCESSED = "processed_notification_ids"
    private const val KEY_REPLY_DAY = "reply_day"
    private const val KEY_DAILY_REPLIES = "daily_replies"
    private const val KEY_REPLY_AI = "reply_ai"
    private const val KEY_REPLY_TEMPLATE = "reply_template"
    private const val KEY_REPLY_LANGUAGE = "reply_language"
    private const val KEY_REPLY_DELAY_SECONDS = "reply_delay_seconds"
    private const val KEY_DAILY_LIMIT = "daily_limit"
    private const val KEY_EXECUTION_LOG = "execution_log"
    private const val KEY_ANNOUNCEMENT_APPS = "announcement_apps"
    private const val KEY_ANNOUNCE_MESSAGE = "announce_message_content"
    private const val KEY_HEADPHONES_ONLY = "announce_headphones_only"
    private const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
    private const val KEY_OBSERVED_APPS = "observed_notification_apps"
    private const val KEY_LAST_NOTIFICATION_AT = "last_notification_at"
    private const val KEY_LISTENER_CONNECTED = "listener_connected"
    private const val KEY_LAST_SPEECH_STATUS = "last_speech_status"
    private const val MAX_HISTORY = 100
    private const val MAX_PROCESSED = 500

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMasterEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MASTER, false)
    fun setMasterEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_MASTER, value).commit()) { "Could not save the master switch." }
    }

    fun isReadingEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_READING, false)
    fun setReadingEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_READING, value).commit()) { "Could not save notification reading settings." }
    }

    fun areAnnouncementsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ANNOUNCEMENTS, false)
    fun setAnnouncementsEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ANNOUNCEMENTS, value).commit()) { "Could not save announcement settings." }
    }

    fun canAnnounceSensitive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENSITIVE_ANNOUNCEMENTS, false)
    fun setAnnounceSensitive(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_SENSITIVE_ANNOUNCEMENTS, value).commit()) {
            "Could not save sensitive announcement settings."
        }
    }

    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)
    fun setPaused(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PAUSED, value).commit()) { "Could not save notification pause state." }
    }

    fun aiRepliesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_REPLY_AI, false)
    fun setAiRepliesEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_REPLY_AI, value).commit()) { "Could not save reply mode." }
    }

    fun replyTemplate(context: Context): String =
        prefs(context).getString(KEY_REPLY_TEMPLATE, "Thanks for your message, {sender}. I will get back to you soon.")
            ?: "Thanks for your message, {sender}. I will get back to you soon."

    fun setReplyTemplate(context: Context, value: String) {
        require(value.isNotBlank() && value.length <= 500) { "Reply template must contain 1 to 500 characters." }
        check(prefs(context).edit().putString(KEY_REPLY_TEMPLATE, value.trim()).commit()) {
            "Could not save the custom reply."
        }
    }

    fun replyLanguage(context: Context): String =
        prefs(context).getString(KEY_REPLY_LANGUAGE, "Auto") ?: "Auto"

    fun setReplyLanguage(context: Context, value: String) {
        require(value in setOf("Auto", "English", "Hindi", "Hinglish")) { "Unsupported reply language." }
        check(prefs(context).edit().putString(KEY_REPLY_LANGUAGE, value).commit()) {
            "Could not save reply language."
        }
    }

    fun replyDelaySeconds(context: Context): Int = prefs(context).getInt(KEY_REPLY_DELAY_SECONDS, 0)
    fun setReplyDelaySeconds(context: Context, value: Int) {
        require(value in 0..300) { "Reply delay must be between 0 and 300 seconds." }
        check(prefs(context).edit().putInt(KEY_REPLY_DELAY_SECONDS, value).commit()) {
            "Could not save reply delay."
        }
    }

    fun dailyReplyLimit(context: Context): Int = prefs(context).getInt(KEY_DAILY_LIMIT, 20)
    fun setDailyReplyLimit(context: Context, value: Int) {
        require(value in 1..500) { "Daily reply limit must be between 1 and 500." }
        check(prefs(context).edit().putInt(KEY_DAILY_LIMIT, value).commit()) {
            "Could not save daily reply limit."
        }
    }

    fun isAppReadingEnabled(context: Context, packageName: String): Boolean {
        return prefs(context).getStringSet(KEY_READING_APPS, emptySet()).orEmpty().contains(packageName)
    }

    fun setAppReadingEnabled(context: Context, packageName: String, enabled: Boolean) {
        val store = prefs(context)
        val apps = store.getStringSet(KEY_READING_APPS, emptySet()).orEmpty().toMutableSet()
        if (enabled) apps.add(packageName) else apps.remove(packageName)
        check(store.edit().putStringSet(KEY_READING_APPS, apps).commit()) {
            "Could not save notification app settings."
        }
    }

    fun monitoredApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_READING_APPS, emptySet()).orEmpty().toSet()

    fun isAnnouncementAppEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getStringSet(KEY_ANNOUNCEMENT_APPS, emptySet()).orEmpty().contains(packageName)

    fun setAnnouncementAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val store = prefs(context)
        val apps = store.getStringSet(KEY_ANNOUNCEMENT_APPS, emptySet()).orEmpty().toMutableSet()
        if (enabled) apps.add(packageName) else apps.remove(packageName)
        check(store.edit().putStringSet(KEY_ANNOUNCEMENT_APPS, apps).commit()) {
            "Could not save per-app announcement settings."
        }
    }

    fun announcesMessageContent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_MESSAGE, false)

    fun setAnnouncesMessageContent(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ANNOUNCE_MESSAGE, enabled).commit()) {
            "Could not save spoken-message content setting."
        }
    }

    fun announcementsHeadphonesOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEADPHONES_ONLY, false)

    fun setAnnouncementsHeadphonesOnly(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_HEADPHONES_ONLY, enabled).commit()) {
            "Could not save headphones-only announcement setting."
        }
    }

    fun historyRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_HISTORY_RETENTION_DAYS, 30).coerceIn(1, 365)

    fun setHistoryRetentionDays(context: Context, days: Int) {
        require(days in 1..365) { "History retention must be between 1 and 365 days." }
        check(prefs(context).edit().putInt(KEY_HISTORY_RETENTION_DAYS, days).commit()) {
            "Could not save notification history retention."
        }
        history(context)
    }

    fun rememberObservedApp(context: Context, packageName: String, receivedAt: Long) {
        require(packageName.isNotBlank())
        val store = prefs(context)
        val packages = store.getStringSet(KEY_OBSERVED_APPS, emptySet()).orEmpty().toMutableSet()
        packages.add(packageName)
        check(store.edit()
            .putStringSet(KEY_OBSERVED_APPS, packages)
            .putLong(KEY_LAST_NOTIFICATION_AT, receivedAt)
            .commit()) { "Could not update notification listener diagnostics." }
    }

    fun observedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_OBSERVED_APPS, emptySet()).orEmpty().toSet()

    fun lastNotificationAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_NOTIFICATION_AT, 0L)

    fun setListenerConnected(context: Context, connected: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_LISTENER_CONNECTED, connected).commit()) {
            "Could not update notification listener diagnostics."
        }
    }

    fun isListenerConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTENER_CONNECTED, false)

    fun setSpeechStatus(context: Context, status: String) {
        check(prefs(context).edit().putString(KEY_LAST_SPEECH_STATUS, status.take(160)).commit()) {
            "Could not save speech diagnostics."
        }
    }

    fun speechStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_SPEECH_STATUS, "No announcement has been played yet.")
            ?: "No announcement has been played yet."

    fun excludedContacts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED_CONTACTS, emptySet()).orEmpty().toSet()

    fun setExcludedContacts(context: Context, values: Set<String>) {
        check(prefs(context).edit().putStringSet(KEY_EXCLUDED_CONTACTS, values.map(String::trim)
            .filter(String::isNotBlank).toSet()).commit()) { "Could not save excluded contacts." }
    }

    fun excludedKeywords(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED_KEYWORDS, emptySet()).orEmpty().toSet()

    fun setExcludedKeywords(context: Context, values: Set<String>) {
        check(prefs(context).edit().putStringSet(KEY_EXCLUDED_KEYWORDS, values.map(String::trim)
            .filter(String::isNotBlank).toSet()).commit()) { "Could not save excluded keywords." }
    }

    fun setQuietHours(context: Context, startMinute: Int?, endMinute: Int?) {
        require(
            startMinute == null && endMinute == null ||
                startMinute != null && endMinute != null &&
                startMinute in 0..1439 && endMinute in 0..1439
        ) { "Quiet-hour times must be between 00:00 and 23:59." }
        val edit = prefs(context).edit()
        if (startMinute == null) edit.remove(KEY_QUIET_START) else edit.putInt(KEY_QUIET_START, startMinute)
        if (endMinute == null) edit.remove(KEY_QUIET_END) else edit.putInt(KEY_QUIET_END, endMinute)
        check(edit.commit()) { "Could not save quiet hours." }
    }

    fun quietHours(context: Context): Pair<Int?, Int?> {
        val store = prefs(context)
        val start = store.getInt(KEY_QUIET_START, -1).takeIf { it in 0..1439 }
        val end = store.getInt(KEY_QUIET_END, -1).takeIf { it in 0..1439 }
        return start to end
    }

    fun isQuietNow(context: Context, nowMinute: Int): Boolean {
        val (start, end) = quietHours(context)
        return NotificationRuleEngine.isQuietAt(start, end, nowMinute)
    }

    fun rules(context: Context): List<NotificationRule> {
        val raw = prefs(context).getString(KEY_RULES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val action = runCatching {
                        NotificationRuleAction.valueOf(item.optString("action"))
                    }.getOrNull() ?: continue
                    val name = item.optString("name").trim()
                    if (name.isBlank()) continue
                    add(NotificationRule(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        packageName = item.optString("package", "*"),
                        keyword = item.optString("keyword"),
                        sender = item.optString("sender"),
                        action = action,
                        template = item.optString("template"),
                        enabled = item.optBoolean("enabled", true),
                        priority = item.optInt("priority")
                    ))
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read notification rules.", error)
        }
    }

    fun saveRule(context: Context, rule: NotificationRule) {
        require(rule.name.isNotBlank()) { "Rule name is required." }
        require(rule.name.length <= 80 && rule.template.length <= 1000) { "Rule fields exceed allowed lengths." }
        val current = rules(context).filterNot { it.id == rule.id } + rule
        val json = JSONArray().apply {
            current.forEach {
                put(JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("package", it.packageName)
                    .put("keyword", it.keyword)
                    .put("sender", it.sender)
                    .put("action", it.action.name)
                    .put("template", it.template)
                    .put("enabled", it.enabled)
                    .put("priority", it.priority))
            }
        }
        check(prefs(context).edit().putString(KEY_RULES, json.toString()).commit()) {
            "Could not save notification rule."
        }
    }

    fun addHistory(context: Context, record: NotificationRecord) {
        synchronized(this) {
            val store = prefs(context)
            val old = history(context).toMutableList()
            if (old.none { it.id == record.id }) old.add(0, record)
            val retained = old.take(MAX_HISTORY)
            val json = JSONArray().apply { retained.forEach { put(it.toJson()) } }
            check(store.edit().putString(KEY_HISTORY, json.toString()).commit()) {
                "Could not save notification history."
            }
        }
    }

    fun upsertNotification(context: Context, record: NotificationRecord) {
        synchronized(this) {
            val store = prefs(context)
            persistHistory(
                store,
                NotificationInboxPolicy.upsertHistory(history(context), record, MAX_HISTORY)
            )
        }
    }

    fun markNotificationRead(context: Context, id: String) = updateNotification(context, id) {
        it.copy(isRead = true)
    }

    fun markAllNotificationsRead(context: Context) {
        synchronized(this) {
            val store = prefs(context)
            persistHistory(store, history(context).map { it.copy(isRead = true) })
        }
    }

    fun markNotificationDismissed(context: Context, notificationKey: String) =
        updateNotification(context, notificationKey, matchByNotificationKey = true) {
            it.copy(dismissed = true)
        }

    private fun updateNotification(
        context: Context,
        id: String,
        matchByNotificationKey: Boolean = false,
        update: (NotificationRecord) -> NotificationRecord
    ) {
        synchronized(this) {
            val store = prefs(context)
            val records = history(context).toMutableList()
            val index = records.indexOfFirst {
                if (matchByNotificationKey) it.notificationKey == id else it.id == id
            }
            if (index < 0) return
            records[index] = update(records[index])
            persistHistory(store, records)
        }
    }

    fun history(context: Context): List<NotificationRecord> {
        val raw = prefs(context).getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val records = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(NotificationRecord(
                        id = item.optString("id"),
                        packageName = item.optString("package"),
                        appName = item.optString("app"),
                        sender = item.optString("sender"),
                        message = item.optString("message"),
                        receivedAt = item.optLong("time"),
                        sensitive = item.optBoolean("sensitive"),
                        notificationKey = item.optString("notification_key", item.optString("id")),
                        isRead = item.optBoolean("read", false),
                        dismissed = item.optBoolean("dismissed", false)
                    ))
                }
            }.filter { it.receivedAt >= retentionCutoff(context) }
                .sortedByDescending(NotificationRecord::receivedAt)
                .take(MAX_HISTORY)
            if (records.size != array.length()) persistHistory(prefs(context), records)
            records
        } catch (error: Exception) {
            throw IllegalStateException("Could not read notification history.", error)
        }
    }

    fun clearHistory(context: Context) {
        check(prefs(context).edit().remove(KEY_HISTORY).commit()) { "Could not clear notification history." }
    }

    private fun retentionCutoff(context: Context): Long =
        System.currentTimeMillis() - historyRetentionDays(context) * 24L * 60L * 60L * 1000L

    private fun persistHistory(
        store: android.content.SharedPreferences,
        records: List<NotificationRecord>
    ) {
        val cutoff = System.currentTimeMillis() -
            store.getInt(KEY_HISTORY_RETENTION_DAYS, 30).coerceIn(1, 365) * 24L * 60L * 60L * 1000L
        val json = JSONArray().apply {
            records.asSequence()
                .filter { it.receivedAt >= cutoff }
                .sortedByDescending(NotificationRecord::receivedAt)
                .take(MAX_HISTORY)
                .forEach { put(it.toJson()) }
        }
        check(store.edit().putString(KEY_HISTORY, json.toString()).commit()) {
            "Could not save notification history."
        }
    }

    fun addExecutionLog(context: Context, description: String) {
        val store = prefs(context)
        val entries = store.getString(KEY_EXECUTION_LOG, "").orEmpty()
            .split('\n')
            .filter(String::isNotBlank)
            .toMutableList()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        entries.add(0, "$stamp • ${description.take(120)}")
        check(store.edit().putString(KEY_EXECUTION_LOG, entries.take(100).joinToString("\n")).commit()) {
            "Could not save notification execution log."
        }
    }

    fun executionLog(context: Context): List<String> =
        prefs(context).getString(KEY_EXECUTION_LOG, "").orEmpty()
            .split('\n')
            .filter(String::isNotBlank)

    fun clearExecutionLog(context: Context) {
        check(prefs(context).edit().remove(KEY_EXECUTION_LOG).commit()) {
            "Could not clear notification execution log."
        }
    }

    fun removeRule(context: Context, id: String) {
        val remaining = rules(context).filterNot { it.id == id }
        val json = JSONArray().apply {
            remaining.forEach { rule ->
                put(JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("package", rule.packageName)
                    .put("keyword", rule.keyword)
                    .put("sender", rule.sender)
                    .put("action", rule.action.name)
                    .put("template", rule.template)
                    .put("enabled", rule.enabled)
                    .put("priority", rule.priority))
            }
        }
        check(prefs(context).edit().putString(KEY_RULES, json.toString()).commit()) {
            "Could not remove notification rule."
        }
    }

    fun isDuplicate(context: Context, notificationKey: String): Boolean {
        val store = prefs(context)
        val keys = store.getStringSet(KEY_PROCESSED, emptySet()).orEmpty().toMutableSet()
        if (notificationKey in keys) return true
        keys.add(notificationKey)
        while (keys.size > MAX_PROCESSED) keys.remove(keys.first())
        check(store.edit().putStringSet(KEY_PROCESSED, keys).commit()) {
            "Could not persist notification duplicate protection."
        }
        return false
    }

    fun claimAnnouncement(
        context: Context,
        notificationKey: String,
        message: String,
        enqueue: () -> Boolean
    ): Boolean = synchronized(this) {
        val store = prefs(context)
        val keys = store.getStringSet(KEY_PROCESSED, emptySet()).orEmpty().toMutableSet()
        val claim = announcementClaim(notificationKey, message)
        if (claim in keys) return@synchronized false
        keys.add(claim)
        while (keys.size > MAX_PROCESSED) keys.remove(keys.first())
        check(store.edit().putStringSet(KEY_PROCESSED, keys).commit()) {
            "Could not persist announcement repeat protection."
        }
        if (!enqueue()) {
            keys.remove(claim)
            check(store.edit().putStringSet(KEY_PROCESSED, keys).commit()) {
                "Could not roll back rejected announcement repeat protection."
            }
            return@synchronized false
        }
        true
    }

    fun releaseAnnouncement(context: Context, notificationKey: String, message: String) {
        synchronized(this) {
            val store = prefs(context)
            val keys = store.getStringSet(KEY_PROCESSED, emptySet()).orEmpty().toMutableSet()
            if (keys.remove(announcementClaim(notificationKey, message))) {
                check(store.edit().putStringSet(KEY_PROCESSED, keys).commit()) {
                    "Could not release failed announcement repeat protection."
                }
            }
        }
    }

    fun dailyReplyCount(context: Context, day: String): Int {
        val store = prefs(context)
        if (store.getString(KEY_REPLY_DAY, null) != day) {
            check(store.edit().putString(KEY_REPLY_DAY, day).putInt(KEY_DAILY_REPLIES, 0).commit()) {
                "Could not reset daily reply count."
            }
            return 0
        }
        return store.getInt(KEY_DAILY_REPLIES, 0)
    }

    @Synchronized
    fun reserveDailyReply(context: Context, day: String): Boolean {
        val store = prefs(context)
        val count = if (store.getString(KEY_REPLY_DAY, null) == day) {
            store.getInt(KEY_DAILY_REPLIES, 0)
        } else 0
        if (count >= dailyReplyLimit(context)) return false
        check(store.edit().putString(KEY_REPLY_DAY, day).putInt(KEY_DAILY_REPLIES, count + 1).commit()) {
            "Could not update daily reply count."
        }
        return true
    }

    private fun NotificationRecord.toJson() = JSONObject()
        .put("id", id).put("package", packageName).put("app", appName)
        .put("sender", sender).put("message", message).put("time", receivedAt)
        .put("sensitive", sensitive).put("notification_key", notificationKey)
        .put("read", isRead).put("dismissed", dismissed)

    private fun announcementClaim(notificationKey: String, message: String): String =
        "announcement:$notificationKey:${MessageDigest.getInstance("SHA-256")
            .digest(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }}"
}
