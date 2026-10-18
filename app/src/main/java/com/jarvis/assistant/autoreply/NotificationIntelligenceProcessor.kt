package com.jarvis.assistant.autoreply

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.security.MessageDigest

object NotificationIntelligenceProcessor {
    private const val TAG = "NotificationIntelligence"
    private const val MAX_TEXT = 2000

    fun process(
        context: Context,
        sbn: StatusBarNotification,
        announce: (String, String) -> Unit
    ) {
        val packageName = sbn.packageName
        if (packageName == context.packageName ||
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            !AutoReplyPolicy.isSupportedPackage(packageName, defaultSmsPackage(context))
        ) return

        val (sender, message) = notificationContent(sbn.notification)
        if (message.isBlank()) return
        val identity = "${sbn.key}:${sbn.postTime}:$sender:$message"
        val recordId = identity.toSha256()
        if (NotificationIntelligenceStore.isDuplicate(context, recordId)) return

        val appName = appName(context, packageName)
        val receivedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val sensitive = AutoReplyPolicy.containsSensitiveContent(message)
        val record = NotificationRecord(
            id = recordId,
            packageName = packageName,
            appName = appName,
            sender = sender,
            message = message.take(MAX_TEXT),
            receivedAt = receivedAt,
            sensitive = sensitive
        )

        val isPaused = NotificationIntelligenceStore.isPaused(context)
        if (isPaused) return

        val readingEnabled = NotificationIntelligenceStore.isReadingEnabled(context) &&
            NotificationIntelligenceStore.isAppReadingEnabled(context, packageName)

        if (readingEnabled) {
            try {
                NotificationIntelligenceStore.addHistory(context, record)
                NotificationIntelligenceStore.addExecutionLog(
                    context,
                    "Received ${if (sensitive) "sensitive " else ""}notification from $appName"
                )
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
        val matching = if (masterEnabled && readingEnabled && !excluded &&
            !NotificationIntelligenceStore.isQuietNow(context, currentMinute())
        ) {
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
        if (masterEnabled && readingEnabled && !excluded &&
            !NotificationIntelligenceStore.isQuietNow(context, currentMinute())
        ) {
            val ignoreRule = matching.firstOrNull { it.action == NotificationRuleAction.IGNORE }
            if (ignoreRule != null) {
                NotificationIntelligenceStore.addExecutionLog(context, "Ignored notification by rule ${ignoreRule.name}")
                return
            }

            matching.forEach { rule ->
                val announcement = if (rule.template.isBlank()) {
                    "$appName message from $sender: $message"
                } else {
                    NotificationRuleEngine.renderTemplate(
                        rule.template, appName, sender, message, record.formattedTime
                    )
                }
                when (rule.action) {
                    NotificationRuleAction.IGNORE -> return
                    NotificationRuleAction.ANNOUNCE ->
                        if (NotificationIntelligenceStore.areAnnouncementsEnabled(context) &&
                            (!sensitive || NotificationIntelligenceStore.canAnnounceSensitive(context))
                        ) {
                            announce(announcement, localeForReply(context))
                            NotificationIntelligenceStore.addExecutionLog(context, "Announced notification by rule ${rule.name}")
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
            if (matching.isEmpty() &&
                NotificationIntelligenceStore.areAnnouncementsEnabled(context) &&
                (!sensitive || NotificationIntelligenceStore.canAnnounceSensitive(context))
            ) {
                announce("$appName message from $sender: $message", localeForReply(context))
            }
        }

        val authorizedReplyRule = matching.firstOrNull {
            it.action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY
        }
        val ruleAllowsAutoReply = matching.isEmpty() || authorizedReplyRule != null
        if (masterEnabled && ruleAllowsAutoReply && !excluded &&
            !hasOutgoingLatestMessage(sbn.notification)
        ) {
            AutoReplyCoordinator.onNotification(
                context,
                packageName,
                sbn.notification,
                authorizedReplyRule?.template?.takeIf(String::isNotBlank)
            )
        }
    }

    private fun notificationContent(notification: Notification): Pair<String, String> {
        val extras = notification.extras ?: return "" to ""
        val sender = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            )?.toString()?.trim().orEmpty().take(120)
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        return sender to text.take(MAX_TEXT)
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

    private fun hasOutgoingLatestMessage(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val bundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty()
        }
        val latest = try {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).lastOrNull()
        } catch (_: Exception) {
            null
        } ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) latest.senderPerson == null
        else {
            @Suppress("DEPRECATION")
            latest.sender == null
        }
    }

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
