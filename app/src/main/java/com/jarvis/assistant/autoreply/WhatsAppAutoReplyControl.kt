package com.jarvis.assistant.autoreply

import android.content.Context
import android.content.pm.PackageManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.PermissionHelper

enum class WhatsAppAutoReplyCommand {
    START,
    STOP,
    STATUS,
    SELECT_PERSONA,
    ENABLE_ULTRON_DEFENSE,
    DISABLE_ULTRON_DEFENSE,
    ULTRON_DEFENSE_STATUS
}

data class WhatsAppAutoReplyInstruction(
    val command: WhatsAppAutoReplyCommand,
    val personaId: String? = null
)

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
    private val ultronDefenseTarget = Regex(
        """\bultron\b.*\b(?:auto[- ]?defen[cs]e|defen[cs]e|abusive|abuse|harass|gaali)\b|\b(?:auto[- ]?defen[cs]e|defen[cs]e)\b.*\bultron\b""",
        RegexOption.IGNORE_CASE
    )
    private val personaPattern = Regex(
        """\b(hope|friday|e\.?\s*d\.?\s*i\.?\s*t\.?\s*h\.?|ultron)\b""",
        RegexOption.IGNORE_CASE
    )
    private val personaActionPattern = Regex(
        """\b(?:activate|select|set|switch|change|handle|active|wapas|waapas|use|karne\s+ke\s+liye|bana\s+do|kar\s+do)\b""",
        RegexOption.IGNORE_CASE
    )
    private val countStatusPattern = Regex(
        """\b(?:kitni\s+baar|how many times|count|history)\b""",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String): WhatsAppAutoReplyCommand? =
        detectInstruction(text)?.command

    fun detectInstruction(text: String): WhatsAppAutoReplyInstruction? {
        if (ultronDefenseTarget.containsMatchIn(text)) {
            val command = when {
                statusPattern.containsMatchIn(text) || countStatusPattern.containsMatchIn(text) ->
                    WhatsAppAutoReplyCommand.ULTRON_DEFENSE_STATUS
                stopPattern.containsMatchIn(text) ->
                    WhatsAppAutoReplyCommand.DISABLE_ULTRON_DEFENSE
                startPattern.containsMatchIn(text) ||
                    Regex("""\b(?:abusive|abuse|harass|gaali)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    WhatsAppAutoReplyCommand.ENABLE_ULTRON_DEFENSE
                else -> return null
            }
            return WhatsAppAutoReplyInstruction(command)
        }

        if (!targetPattern.containsMatchIn(text)) return null
        val persona = personaPattern.findAll(text).lastOrNull()
        if (persona != null && personaActionPattern.containsMatchIn(text)) {
            val personaId = when (persona.value.replace(Regex("""[.\s]"""), "").lowercase()) {
                "hope" -> com.jarvis.assistant.ai.PersonaEngine.HOPE
                "friday" -> com.jarvis.assistant.ai.PersonaEngine.FRIDAY
                "edith" -> com.jarvis.assistant.ai.PersonaEngine.EDITH
                "ultron" -> com.jarvis.assistant.ai.PersonaEngine.ULTRON
                else -> return null
            }
            return WhatsAppAutoReplyInstruction(
                WhatsAppAutoReplyCommand.SELECT_PERSONA,
                personaId
            )
        }
        val command = when {
            statusPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.STATUS
            stopPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.STOP
            startPattern.containsMatchIn(text) -> WhatsAppAutoReplyCommand.START
            else -> return null
        }
        return WhatsAppAutoReplyInstruction(command)
    }
}

object WhatsAppAutoReplyControl {
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    fun execute(context: Context, command: WhatsAppAutoReplyCommand): WhatsAppAutoReplyCommandResult {
        return execute(context, WhatsAppAutoReplyInstruction(command))
    }

    fun execute(
        context: Context,
        instruction: WhatsAppAutoReplyInstruction
    ): WhatsAppAutoReplyCommandResult {
        val appContext = context.applicationContext
        return when (instruction.command) {
            WhatsAppAutoReplyCommand.STOP -> {
                AutoReplyPreferences.setAppEnabled(
                    appContext,
                    AutoReplyCoordinator.WHATSAPP_SETTING_KEY,
                    false
                )
                AutoReplyCoordinator.cancelPendingWhatsAppReplies()
                AutoReplyPreferences.clearUltronDefenseSessions(appContext)
                log(appContext, "WhatsApp auto-reply disabled by user command.")
                WhatsAppAutoReplyCommandResult(
                    true,
                    "WhatsApp auto-reply is off. This also disables WhatsApp Business because both use the shared WhatsApp auto-reply control. Other apps are unchanged."
                )
            }
            WhatsAppAutoReplyCommand.STATUS -> status(appContext)
            WhatsAppAutoReplyCommand.START -> start(appContext)
            WhatsAppAutoReplyCommand.SELECT_PERSONA -> selectPersona(appContext, instruction.personaId)
            WhatsAppAutoReplyCommand.ENABLE_ULTRON_DEFENSE -> setUltronDefense(appContext, enabled = true)
            WhatsAppAutoReplyCommand.DISABLE_ULTRON_DEFENSE -> setUltronDefense(appContext, enabled = false)
            WhatsAppAutoReplyCommand.ULTRON_DEFENSE_STATUS -> ultronDefenseStatus(appContext)
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
        // With HOPE Smart Auto-Reply no rule is needed: starting auto-reply by voice turns smart mode on.
        val ruleMatchedPackage = packages.firstOrNull { packageName ->
            rulePackages.any { it == "*" || it.equals(packageName, ignoreCase = true) }
        }
        val useSmartMode = ruleMatchedPackage == null
        val configuredPackage = ruleMatchedPackage ?: packages.first()

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
        val requiresAiGeneration = useSmartMode ||
            (NotificationIntelligenceStore.aiRepliesEnabled(context) &&
                authorizedRules.any { it.template.isBlank() })
        if (requiresAiGeneration && preferences.apiKey.isBlank()) {
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
            if (useSmartMode) {
                NotificationIntelligenceStore.setAiRepliesEnabled(context, true)
                NotificationIntelligenceStore.setPaused(context, false)
                packages.forEach {
                    NotificationIntelligenceStore.setAppReadingEnabled(context, it, true)
                }
                AutoReplyPreferences.setSmartModeEnabled(context, true)
            }
        } catch (error: Exception) {
            return blocked("HOPE could not save the WhatsApp auto-reply settings: ${error.message ?: "storage error"}.")
        }
        log(context, "WhatsApp auto-reply enabled by explicit user command.")
        return WhatsAppAutoReplyCommandResult(
            true,
            "WhatsApp auto-reply is on for ${AutoReplyCoordinator.appName(configuredPackage)}. It will reply only when an incoming notification contains a supported inline reply action and all safety rules allow it. Delivery cannot be confirmed by HOPE."
        )
    }

    private fun selectPersona(context: Context, personaId: String?): WhatsAppAutoReplyCommandResult {
        val requested = personaId?.let { requestedId ->
            com.jarvis.assistant.ai.PersonaEngine.profiles.firstOrNull {
                it.id.equals(requestedId.trim(), ignoreCase = true)
            }
        }
            ?: return blocked("Choose HOPE, FRIDAY, E.D.I.T.H., or ULTRON for the WhatsApp agent.")
        return try {
            AutoReplyPreferences.setSelectedPersona(context, requested.id)
            log(context, "WhatsApp reply persona changed to ${requested.identityName}.")
            WhatsAppAutoReplyCommandResult(
                true,
                "${requested.identityName} is now the selected WhatsApp reply persona. Other app personas are unchanged."
            )
        } catch (error: Exception) {
            blocked("Could not save the WhatsApp persona: ${error.message ?: "settings unavailable"}.")
        }
    }

    private fun setUltronDefense(context: Context, enabled: Boolean): WhatsAppAutoReplyCommandResult {
        if (enabled && (
                !AutoReplyPreferences.isSmartModeEnabled(context) ||
                    !NotificationIntelligenceStore.isMasterEnabled(context) ||
                    !PermissionHelper.isNotificationListenerEnabled(context) ||
                    !NotificationIntelligenceStore.isListenerConnected(context) ||
                    !AutoReplyPreferences.isAppEnabled(context, AutoReplyCoordinator.WHATSAPP_SETTING_KEY)
                )
        ) {
            return blocked(
                "Enable WhatsApp Smart Auto-Reply and connect HOPE Notification Access before enabling ULTRON Auto-Defense."
            )
        }
        return try {
            AutoReplyPreferences.setUltronAutoDefenseEnabled(context, enabled)
            log(
                context,
                if (enabled) "ULTRON Auto-Defense enabled for WhatsApp." else "ULTRON Auto-Defense disabled by user."
            )
            WhatsAppAutoReplyCommandResult(
                true,
                if (enabled) {
                    "ULTRON Auto-Defense is on for WhatsApp only. It activates only after high-confidence contextual abuse detection, and the original persona remains selected globally."
                } else {
                    "ULTRON Auto-Defense is off. Active defense sessions were cleared."
                }
            )
        } catch (error: Exception) {
            blocked("Could not update ULTRON Auto-Defense: ${error.message ?: "settings unavailable"}.")
        }
    }

    private fun ultronDefenseStatus(context: Context): WhatsAppAutoReplyCommandResult =
        WhatsAppAutoReplyCommandResult(
            true,
            "ULTRON Auto-Defense is ${if (AutoReplyPreferences.isUltronAutoDefenseEnabled(context)) "on" else "off"} for WhatsApp. Timeout: ${AutoReplyPreferences.ultronDefenseTimeoutMinutes(context)} minutes. It never changes the globally selected persona."
        )

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
        val smartMode = AutoReplyPreferences.isSmartModeEnabled(context)
        val enabled = NotificationIntelligenceStore.isMasterEnabled(context) &&
            !NotificationIntelligenceStore.isPaused(context) &&
            NotificationIntelligenceStore.isReadingEnabled(context) &&
            AutoReplyCoordinator.isAppEnabled(context, WHATSAPP) &&
            monitoredPackages.isNotEmpty() &&
            (hasRule || smartMode) &&
            PermissionHelper.isNotificationListenerEnabled(context) &&
            NotificationIntelligenceStore.isListenerConnected(context)
        val details = buildList {
            add("Notification Access ${if (PermissionHelper.isNotificationListenerEnabled(context)) "granted" else "missing"}")
            add("listener ${if (NotificationIntelligenceStore.isListenerConnected(context)) "connected" else "not connected"}")
            add("master switch ${if (NotificationIntelligenceStore.isMasterEnabled(context)) "on" else "off"}")
            add("WhatsApp monitoring ${if (monitoredPackages.isNotEmpty()) "on" else "off"}")
            add(if (smartMode) "HOPE Smart Auto-Reply on (no rule needed)" else "authorized rule ${if (hasRule) "configured" else "missing"}")
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
