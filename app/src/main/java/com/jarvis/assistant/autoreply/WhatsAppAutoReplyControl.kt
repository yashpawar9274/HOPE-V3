package com.jarvis.assistant.autoreply

import android.content.Context
import android.content.pm.PackageManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.util.PermissionHelper

enum class WhatsAppAutoReplyCommand {
    START,
    STOP,
    STATUS
}

data class WhatsAppAutoReplyCommandResult(
    val success: Boolean,
    val message: String
)

object WhatsAppAutoReplyCommandPolicy {
    private val targetPattern = Regex(
        """\b(whats\s?app|auto[- ]?repl(?:y|ies)|automatic(?:ally)?\s+repl(?:y|ies)|khud\s+se\s+reply)\b""",
        RegexOption.IGNORE_CASE
    )
    private val statusPattern = Regex(
        """\b(status|is it on|is it enabled|check status|batao|bataiye)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stopPattern = Regex(
        """\b(stop|disable|turn off|switch off|band karo|band kar do|rok do|pause)\b""",
        RegexOption.IGNORE_CASE
    )
    private val startPattern = Regex(
        """\b(start|enable|turn on|switch on|khud se|automatically|automatic|shuru karo|chalu karo|on karo)\b""",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String): WhatsAppAutoReplyCommand? {
        if (!targetPattern.containsMatchIn(text)) return null
        return when {
            statusPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.STATUS
            stopPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.STOP
            startPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.START
            else -> null
        }
    }
}

object WhatsAppAutoReplyControl {
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    fun execute(context: Context, command: WhatsAppAutoReplyCommand): WhatsAppAutoReplyCommandResult {
        val appContext = context.applicationContext
        return when (command) {
            WhatsAppAutoReplyCommand.STOP -> {
                AutoReplyPreferences.setAppEnabled(
                    appContext,
                    AutoReplyCoordinator.WHATSAPP_SETTING_KEY,
                    false
                )
                AutoReplyCoordinator.cancelPendingWhatsAppReplies()
                log(appContext, "WhatsApp auto-reply disabled by user command.")
                WhatsAppAutoReplyCommandResult(
                    true,
                    "WhatsApp auto-reply is off. This also disables WhatsApp Business because both use the shared WhatsApp auto-reply control. Other apps are unchanged."
                )
            }
            WhatsAppAutoReplyCommand.STATUS -> status(appContext)
            WhatsAppAutoReplyCommand.START -> start(appContext)
        }
    }

    private fun start(context: Context): WhatsAppAutoReplyCommandResult {
        val packages = installedWhatsAppPackages(context)
        if (packages.isEmpty()) {
            return blocked("WhatsApp or WhatsApp Business is not installed.")
        }
        if (!PermissionHelper.isNotificationListenerEnabled(context)) {
            return blocked("Grant HOPE Notification Access in Android Settings, then ask again.")
        }
        if (!NotificationIntelligenceStore.isListenerConnected(context)) {
            return blocked("Notification Access is granted, but Android has not connected HOPE's listener yet. Return to HOPE and check the status before starting.")
        }
        if (NotificationIntelligenceStore.isPaused(context)) {
            return blocked("Notification intelligence is paused. Resume it in AI Auto-Reply settings, then ask again.")
        }

        val rulePackages = try {
            NotificationIntelligenceStore.rules(context)
                .filter { it.enabled && it.action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY }
                .map(NotificationRule::packageName)
        } catch (error: Exception) {
            return blocked("HOPE could not read the auto-reply rules: ${error.message ?: "settings unavailable"}.")
        }
        val configuredPackage = packages.firstOrNull { packageName ->
            rulePackages.any { it == "*" || it.equals(packageName, ignoreCase = true) }
        } ?: return blocked("Create and enable an AUTHORIZED_AUTO_REPLY rule for WhatsApp or WhatsApp Business in AI Auto-Reply settings.")

        if (!NotificationIntelligenceStore.isReadingEnabled(context)) {
            val otherMonitoredApps = NotificationIntelligenceStore.monitoredApps(context)
                .filterNot { it == WHATSAPP || it == WHATSAPP_BUSINESS }
            if (otherMonitoredApps.isNotEmpty()) {
                return blocked(
                    "Global notification reading is off and other apps are already selected (${otherMonitoredApps.joinToString()}). Enable Read Notifications manually after reviewing that app list, then ask again."
                )
            }
        }

        val preferences = (context.applicationContext as? JarvisApp)?.preferences
            ?: return blocked("HOPE's AI settings are unavailable. Open the app and try again.")
        val authorizedRules = NotificationIntelligenceStore.rules(context).filter {
            it.enabled &&
                it.action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY &&
                (it.packageName == "*" || it.packageName.equals(configuredPackage, ignoreCase = true))
        }
        if (NotificationIntelligenceStore.aiRepliesEnabled(context) &&
            preferences.aiMode == AiMode.ONLINE &&
            preferences.apiKey.isBlank() &&
            authorizedRules.any { it.template.isBlank() }
        ) {
            return blocked("AI replies are enabled but no Gemini API key is configured. Add the key in AI settings or use a saved reply template.")
        }

        val otherAppsEnabled = listOf(
            AutoReplyCoordinator.INSTAGRAM_SETTING_KEY to "Instagram",
            AutoReplyCoordinator.SMS_SETTING_KEY to "the selected SMS app"
        ).filter { (key, _) -> AutoReplyPreferences.isAppEnabled(context, key) }
            .map { it.second }
        if (!NotificationIntelligenceStore.isMasterEnabled(context) && otherAppsEnabled.isNotEmpty()) {
            return blocked(
                "The master switch is off and would also enable auto-replies for ${otherAppsEnabled.joinToString()}. Review those app controls and enable the master switch in AI Auto-Reply settings to avoid activating them unintentionally."
            )
        }

        try {
            NotificationIntelligenceStore.setAppReadingEnabled(context, configuredPackage, true)
            if (!NotificationIntelligenceStore.isReadingEnabled(context)) {
                NotificationIntelligenceStore.setReadingEnabled(context, true)
            }
            AutoReplyPreferences.setAppEnabled(context, AutoReplyCoordinator.WHATSAPP_SETTING_KEY, true)
            NotificationIntelligenceStore.setMasterEnabled(context, true)
        } catch (error: Exception) {
            return blocked("HOPE could not save the WhatsApp auto-reply settings: ${error.message ?: "storage error"}.")
        }
        log(context, "WhatsApp auto-reply enabled by explicit user command.")
        return WhatsAppAutoReplyCommandResult(
            true,
            "WhatsApp auto-reply is on for ${AutoReplyCoordinator.appName(configuredPackage)}. It will reply only when an incoming notification contains a supported inline reply action and all safety rules allow it. Delivery cannot be confirmed by HOPE."
        )
    }

    private fun status(context: Context): WhatsAppAutoReplyCommandResult {
        val installedPackages = installedWhatsAppPackages(context)
        val monitoredPackages = installedPackages.filter {
            NotificationIntelligenceStore.isAppReadingEnabled(context, it)
        }
        val hasRule = try {
            NotificationIntelligenceStore.rules(context).any {
                it.enabled &&
                    it.action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY &&
                    (it.packageName == "*" || installedPackages.any { packageName ->
                        it.packageName.equals(packageName, ignoreCase = true)
                    })
            }
        } catch (error: Exception) {
            return WhatsAppAutoReplyCommandResult(
                false,
                "Could not read WhatsApp auto-reply status because the rule settings are invalid: ${error.message ?: "unknown settings error"}."
            )
        }
        val enabled = NotificationIntelligenceStore.isMasterEnabled(context) &&
            !NotificationIntelligenceStore.isPaused(context) &&
            NotificationIntelligenceStore.isReadingEnabled(context) &&
            AutoReplyCoordinator.isAppEnabled(context, WHATSAPP) &&
            monitoredPackages.isNotEmpty() &&
            hasRule &&
            PermissionHelper.isNotificationListenerEnabled(context) &&
            NotificationIntelligenceStore.isListenerConnected(context)
        val details = buildList {
            add("Notification Access ${if (PermissionHelper.isNotificationListenerEnabled(context)) "granted" else "missing"}")
            add("listener ${if (NotificationIntelligenceStore.isListenerConnected(context)) "connected" else "not connected"}")
            add("master switch ${if (NotificationIntelligenceStore.isMasterEnabled(context)) "on" else "off"}")
            add("WhatsApp monitoring ${if (monitoredPackages.isNotEmpty()) "on" else "off"}")
            add("authorized rule ${if (hasRule) "configured" else "missing"}")
            if (NotificationIntelligenceStore.isPaused(context)) add("notification intelligence paused")
        }.joinToString("; ")
        return WhatsAppAutoReplyCommandResult(
            true,
            "WhatsApp auto-reply is ${if (enabled) "ON" else "not ready"} ($details). Last result: ${NotificationIntelligenceStore.lastAutoReplyStatus(context)} A supported notification quick-reply action is still required for each message."
        )
    }

    private fun installedWhatsAppPackages(context: Context): List<String> =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).filter { packageName ->
            try {
                context.packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun blocked(message: String) = WhatsAppAutoReplyCommandResult(false, message)

    private fun log(context: Context, message: String) {
        NotificationIntelligenceStore.addExecutionLog(context, message)
    }
}
