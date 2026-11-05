package com.jarvis.assistant.autoreply

import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * One-switch setup for HOPE Smart Auto-Reply.
 *
 * Turning it on enables every gate the reply pipeline checks (master switch, notification reading,
 * per-app reading and per-app reply switches, AI replies) for the supported chat apps, so the user
 * never has to create a rule or a template. Turning it off stops automatic sending immediately.
 * Safety checks stay active: sensitive-message skip, quiet hours, exclusions, daily limit,
 * per-chat cooldown, group-chat skip and duplicate protection.
 */
object SmartAutoReply {
    private const val TAG = "SmartAutoReply"

    val supportedPackages: List<String> = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    fun isEnabled(context: Context): Boolean = AutoReplyPreferences.isSmartModeEnabled(context)

    private fun allPackages(context: Context): Set<String> {
        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the default SMS app.", error)
            null
        }
        return (supportedPackages + listOfNotNull(sms)).toSet()
    }

    fun enable(context: Context) {
        val app = context.applicationContext
        NotificationIntelligenceStore.setMasterEnabled(app, true)
        NotificationIntelligenceStore.setReadingEnabled(app, true)
        NotificationIntelligenceStore.setAiRepliesEnabled(app, true)
        NotificationIntelligenceStore.setPaused(app, false)
        allPackages(app).forEach { NotificationIntelligenceStore.setAppReadingEnabled(app, it, true) }
        listOf(
            AutoReplyCoordinator.WHATSAPP_SETTING_KEY,
            AutoReplyCoordinator.INSTAGRAM_SETTING_KEY,
            AutoReplyCoordinator.SMS_SETTING_KEY
        ).forEach { AutoReplyPreferences.setAppEnabled(app, it, true) }
        AutoReplyPreferences.setSmartModeEnabled(app, true)
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        AutoReplyPreferences.setSmartModeEnabled(app, false)
        AutoReplyCoordinator.cancelPendingReplies()
    }

    /**
     * True when the notification is a group chat (HOPE never auto-replies into groups).
     * WhatsApp may put a conversation title on 1:1 chats too, where the title is the contact
     * themselves; in a real group the title is the group name and differs from the person writing.
     */
    fun isGroupConversation(
        packageName: String,
        isGroupFlag: Boolean,
        conversationTitle: CharSequence?,
        latestSender: CharSequence? = null
    ): Boolean {
        if (isGroupFlag) return true
        val isWhatsApp = packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"
        val title = conversationTitle?.toString()?.trim().orEmpty()
        if (!isWhatsApp || title.isEmpty()) return false
        val sender = latestSender?.toString()?.trim().orEmpty()
        return sender.isNotEmpty() && !title.equals(sender, ignoreCase = true)
    }
}
