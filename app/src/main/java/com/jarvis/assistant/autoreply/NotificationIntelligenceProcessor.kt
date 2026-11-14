package com.jarvis.assistant.autoreply

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.security.MessageDigest

object NotificationIntelligenceProcessor {
    private const val TAG = "NotificationIntelligence"
    private const val MAX_TEXT = 2000
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun process(
        context: Context,
        sbn: StatusBarNotification,
        announce: (String, String, (Boolean) -> Unit) -> Boolean,
        allowAutoReply: Boolean = true
    ) {
        val packageName = sbn.packageName
        if (packageName == context.packageName ||
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            hasOutgoingLatestMessage(sbn.notification)
        ) return

        val (sender, message) = notificationContent(sbn.notification)

        val appName = appName(context, packageName)
        val receivedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val sensitive = AutoReplyPolicy.containsSensitiveContent(message)
        val record = NotificationRecord(
            id = sbn.key.toSha256(),
            packageName = packageName,
            appName = appName,
            sender = sender,
            message = message.take(MAX_TEXT),
            receivedAt = receivedAt,
            sensitive = sensitive,
            notificationKey = sbn.key
        )

        if (NotificationIntelligenceStore.isPaused(context)) return

        val readingEnabled = NotificationIntelligenceStore.isReadingEnabled(context) &&
            NotificationIntelligenceStore.isAppReadingEnabled(context, packageName)
        val contactProfilesEnabled =
            NotificationIntelligenceStore.areContactReplyProfilesEnabled(context) &&
                readingEnabled &&
                AutoReplyPolicy.isSupportedPackage(packageName, defaultSmsPackage(context)) &&
                !isGroupNotification(sbn, sender)
        val replyIdentity = if (contactProfilesEnabled && sender.isNotBlank()) {
            resolveReplyIdentity(sbn, sender)
        } else null
        if (replyIdentity != null) {
            profileScope.launch {
                try {
                    val app = context.applicationContext as JarvisApp
                    app.contactReplyProfileRepository.recordDiscoveredContact(packageName, replyIdentity)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not save an opted-in local contact identity.", error)
                }
            }
        }

        if (readingEnabled) {
            try {
                NotificationIntelligenceStore.upsertNotification(context, record)
            } catch (error: Exception) {
                Log.e(TAG, "Could not persist local notification history.", error)
            }
        }
        val excludedContact = NotificationIntelligenceStore.excludedContacts(context)
            .any { sender.contains(it, ignoreCase = true) }
        val excludedKeyword = NotificationIntelligenceStore.excludedKeywords(context)
            .any { message.contains(it, ignoreCase = true) }
        val excluded = excludedContact || excludedKeyword
        val masterEnabled = NotificationIntelligenceStore.isMasterEnabled(context)
        val quiet = NotificationIntelligenceStore.isQuietNow(context, currentMinute())
        val matching = if (readingEnabled && !excluded && !quiet) {
            try {
                NotificationRuleEngine.matchingRules(
                    NotificationRuleInput(packageName, sender, message),
                    NotificationIntelligenceStore.rules(context).map { rule ->
                        if (rule.packageName == "sms") {
                            rule.copy(packageName = defaultSmsPackage(context) ?: rule.packageName)
                        } else rule
                    }
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not evaluate notification rules.", error)
                emptyList()
            }
        } else emptyList()
        if (readingEnabled && !excluded && !quiet) {
            val ignoreRule = matching.firstOrNull { it.action == NotificationRuleAction.IGNORE }
            if (ignoreRule != null) {
                NotificationIntelligenceStore.addExecutionLog(context, "Ignored notification by rule ${ignoreRule.name}")
                return
            }

            val announceAppEnabled = NotificationIntelligenceStore.areAnnouncementsEnabled(context) &&
                NotificationIntelligenceStore.isAnnouncementAppEnabled(context, packageName)
            var ruleAnnouncementHandled = false
            matching.forEach { rule ->
                val announcement = if (rule.template.isBlank()) {
                    announcementText(context, appName, sender, message)
                } else {
                    NotificationRuleEngine.renderTemplate(
                        rule.template,
                        appName,
                        sender,
                        if (NotificationIntelligenceStore.announcesMessageContent(context)) message else "",
                        record.formattedTime
                    )
                }
                when (rule.action) {
                    NotificationRuleAction.IGNORE -> return
                    NotificationRuleAction.ANNOUNCE -> {
                        if (!ruleAnnouncementHandled && announceAppEnabled &&
                            (!sensitive || NotificationIntelligenceStore.canAnnounceSensitive(context))
                        ) {
                            queueAnnouncement(
                                context, appName, announcement, localeForReply(context), sbn.key, announce
                            )
                        }
                        ruleAnnouncementHandled = true
                    }
                    NotificationRuleAction.DISPLAY -> {
                        postRuleNotification(context, appName, sender, announcement)
                        NotificationIntelligenceStore.addExecutionLog(context, "Displayed notification by rule ${rule.name}")
                    }
                    NotificationRuleAction.SUGGEST_REPLY -> {
                        postSuggestion(context, appName, sender, announcement)
                        NotificationIntelligenceStore.addExecutionLog(context, "Suggested reply by rule ${rule.name}")
                    }
                    NotificationRuleAction.AUTHORIZED_AUTO_REPLY -> Unit
                }
            }
            if (!ruleAnnouncementHandled &&
                announceAppEnabled &&
                (!sensitive || NotificationIntelligenceStore.canAnnounceSensitive(context))
            ) {
                queueAnnouncement(
                    context,
                    appName,
                    announcementText(context, appName, sender, message),
                    localeForReply(context),
                    sbn.key,
                    announce
                )
            }
        }

        val authorizedReplyRule = NotificationRuleEngine.authorizedAutoReplyRule(matching)
        if (allowAutoReply &&
            masterEnabled && readingEnabled && AutoReplyCoordinator.isAppEnabled(context, packageName) &&
            AutoReplyPolicy.isSupportedPackage(packageName, defaultSmsPackage(context)) &&
            (authorizedReplyRule != null || AutoReplyPreferences.isSmartModeEnabled(context) ||
                (contactProfilesEnabled && replyIdentity != null)) &&
            !excluded && !quiet &&
            !hasOutgoingLatestMessage(sbn.notification)
        ) {
            AutoReplyCoordinator.onNotification(
                context,
                packageName,
                sbn.notification,
                authorizedReplyRule,
                replyIdentity
            )
        } else if (allowAutoReply && AutoReplyPreferences.isSmartModeEnabled(context) &&
            AutoReplyPolicy.isSupportedPackage(packageName, defaultSmsPackage(context))
        ) {
            val reason = when {
                !masterEnabled -> "the AI Auto Reply master switch is off"
                !readingEnabled -> "notification reading is off for this app"
                !AutoReplyCoordinator.isAppEnabled(context, packageName) -> "replies are off for this app"
                excluded -> "the sender or message is excluded"
                quiet -> "quiet hours are active"
                else -> "the message looks like one you sent"
            }
            try {
                NotificationIntelligenceStore.setLastAutoReplyStatus(
                    context,
                    "$appName \u2022 Skipped: $reason."
                )
            } catch (error: Exception) {
                Log.w(TAG, "Could not save the auto-reply status.", error)
            }
        }
    }

    private fun announcementText(
        context: Context,
        appName: String,
        sender: String,
        message: String
    ): String {
        val greeting = if (sender.isBlank()) {
            "HOPE here. You have a new $appName notification."
        } else {
            "HOPE here. You have a new $appName message from $sender."
        }
        return if (NotificationIntelligenceStore.announcesMessageContent(context) && message.isNotBlank()) {
            "$greeting $message"
        } else greeting
    }

    private fun queueAnnouncement(
        context: Context,
        appName: String,
        announcement: String,
        language: String,
        notificationKey: String,
        announce: (String, String, (Boolean) -> Unit) -> Boolean
    ): Boolean {
        if (AutoReplyPolicy.containsSensitiveContent(announcement) &&
            !NotificationIntelligenceStore.canAnnounceSensitive(context)
        ) return false
        val queued = NotificationIntelligenceStore.claimAnnouncement(
            context,
            notificationKey,
            announcement
        ) {
            announce(announcement, language) { completed ->
                if (!completed) {
                    try {
                        NotificationIntelligenceStore.releaseAnnouncement(
                            context,
                            notificationKey,
                            announcement
                        )
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not clear failed announcement repeat protection.", error)
                    }
                }
            }
        }
        if (!queued) return false
        NotificationIntelligenceStore.addExecutionLog(
            context,
            "Queued voice announcement for $appName"
        )
        return queued
    }

    private fun notificationContent(notification: Notification): Pair<String, String> {
        val extras = notification.extras ?: return "" to ""
        val latestIncoming = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages?.lastOrNull { messageSender(it).isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the latest incoming MessagingStyle message", error)
            null
        }

        val sender = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            )?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: latestIncoming?.let(::messageSender).orEmpty()
        val safeSender = sender.take(120)
        val previewText = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)
            .orEmpty()
        val text = AutoReplyPolicy.notificationTextOrLatestMessage(
            previewText,
            listOfNotNull(latestIncoming?.text?.toString())
        )
        return safeSender to text.take(MAX_TEXT)
    }

    private fun resolveReplyIdentity(
        sbn: StatusBarNotification,
        displayName: String
    ): ReplyContactIdentity? {
        val latestIncoming = try {
            if (sbn.notification.extras == null) return null
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                ?.messages?.lastOrNull { messageSender(it).isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not extract a stable contact identifier from notification messages.", error)
            null
        }

        val phoneAddress = latestIncoming?.person?.uri?.toString()
        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sbn.notification.shortcutId
        } else null
        return try {
            ReplyContactIdentityResolver.resolve(
                platformPackage = sbn.packageName,
                shortcutId = shortcutId,
                phoneAddress = phoneAddress,
                notificationKey = sbn.key,
                displayName = displayName
            )
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not resolve a stable notification contact identity.", error)
            null
        }
    }

    private fun isGroupNotification(sbn: StatusBarNotification, sender: String): Boolean {
        val extras = sbn.notification.extras
        return SmartAutoReply.isGroupConversation(
            sbn.packageName,
            extras?.getBoolean("android.isGroupConversation", false) == true,
            extras?.getCharSequence("android.conversationTitle"),
            sender
        )
    }

    private fun appName(context: Context, packageName: String): String {
        val known = AutoReplyCoordinator.appName(packageName)
        if (known != packageName) return known
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString().take(80)
        } catch (_: Exception) {
            packageName
        }
    }

    private fun currentMinute(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun String.toSha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun localeForReply(context: Context): String =
        com.jarvis.assistant.telephony.IncomingCallPreferences.language(context)

    private fun defaultSmsPackage(context: Context): String? =
        try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(context)
        } catch (error: Exception) {
            Log.w(TAG, "Could not determine default SMS app.", error)
            null
        }

    @Suppress("DEPRECATION")
    private fun messageSender(message: NotificationCompat.MessagingStyle.Message): String =
        (message.person?.name ?: message.sender)?.toString()?.trim().orEmpty()

    private fun hasOutgoingLatestMessage(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val latest = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages?.lastOrNull()
        } catch (_: Exception) {
            null
        } ?: return false
        return isOutgoingMessage(latest)
    }

    @Suppress("DEPRECATION")
    private fun isOutgoingMessage(message: NotificationCompat.MessagingStyle.Message): Boolean =
        message.person == null && message.sender.isNullOrBlank()

    private fun postRuleNotification(context: Context, appName: String, sender: String, message: String) {
        AutoReplyCoordinator.postIntelligenceNotification(
            context, "$appName notification", sender, message
        )
    }

    private fun postSuggestion(context: Context, appName: String, sender: String, suggestion: String) {
        AutoReplyCoordinator.postIntelligenceNotification(
            context, "$appName reply suggestion", sender, suggestion
        )
    }
}
