package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.app.TimePickerDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.autoreply.AutoReplyCoordinator
import com.jarvis.assistant.autoreply.AutoReplyPreferences
import com.jarvis.assistant.autoreply.NotificationIntelligenceStore
import com.jarvis.assistant.autoreply.NotificationRule
import com.jarvis.assistant.autoreply.NotificationRuleAction
import com.jarvis.assistant.autoreply.NotificationRuleEngine
import com.jarvis.assistant.autoreply.NotificationRuleInput
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.HopeSpeech
import com.jarvis.assistant.util.ThemeManager

class AutoReplySettingsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var replyStatus: TextView

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(root)

        addText("AI AUTO-REPLY", 23f, bold = true)
        addText(
            "Automatic sending requires Notification Access, global and per-app monitoring, the auto-reply master switch, an explicitly selected app, a matching AUTHORIZED_AUTO_REPLY rule, a supported inline-reply action, and a nonsensitive message. Without that rule, HOPE never sends automatically.",
            14f
        )

        serviceStatus = addText("", 13f)
        addButton("Grant HOPE notification access") {
            try {
                PermissionHelper.openNotificationListenerSettings(this)
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    "Could not open notification access settings: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        notificationStatus = addText("", 13f)
        addButton("Allow JARVIS notifications") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !PermissionHelper.hasNotificationPermission(this)
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    packageName
                ))
            }
        }

        addText("MASTER PRIVACY CONTROL", 12f, bold = true)
        addSettingSwitch(
            "AI Auto Reply master switch",
            "Emergency OFF stops automatic message sending. Monitoring, inbox history, rules, and announcements use their separate controls below.",
            NotificationIntelligenceStore.isMasterEnabled(this)
        ) { enabled -> NotificationIntelligenceStore.setMasterEnabled(this, enabled) }
        addSettingSwitch(
            "Read Notifications",
            "Enable notification monitoring globally; select individual apps in Per-app notification controls.",
            NotificationIntelligenceStore.isReadingEnabled(this)
        ) { enabled -> NotificationIntelligenceStore.setReadingEnabled(this, enabled) }
        addSettingSwitch(
            "Announce notifications aloud",
            "Allow announcements globally. Per-app announcement controls and the intelligence pause switch also apply.",
            NotificationIntelligenceStore.areAnnouncementsEnabled(this)
        ) { enabled -> NotificationIntelligenceStore.setAnnouncementsEnabled(this, enabled) }
        addSettingSwitch(
            "Allow sensitive notifications to be spoken",
            "Off by default. Sensitive notifications are still never auto-replied to.",
            NotificationIntelligenceStore.canAnnounceSensitive(this)
        ) { enabled -> NotificationIntelligenceStore.setAnnounceSensitive(this, enabled) }
        addButton(if (NotificationIntelligenceStore.isPaused(this)) "Resume notification intelligence" else "Pause notification intelligence") {
            NotificationIntelligenceStore.setPaused(this, !NotificationIntelligenceStore.isPaused(this))
            recreate()
        }

        addButton("Per-app notification controls and diagnostics") {
            startActivity(Intent(this, NotificationAppsActivity::class.java))
        }
        addButton("Open real notification inbox") {
            startActivity(Intent(this, NotificationInboxActivity::class.java))
        }
        addButton("History retention: ${NotificationIntelligenceStore.historyRetentionDays(this)} days") {
            chooseHistoryRetention()
        }

        addText("AUTO-REPLY SCOPE & RESPONSE", 12f, bold = true)
        addText(
            "Automatic replies are off by default. Each send requires an explicitly enabled app and a matching AUTHORIZED_AUTO_REPLY rule. Use SUGGEST_REPLY for draft-only behavior.",
            13f
        )
        replyStatus = addText("", 13f, bold = true)
        addSettingSwitch(
            "Use AI-generated contextual replies",
            "When on, message context is sent to your configured Gemini service. Off uses the saved reply template on-device.",
            NotificationIntelligenceStore.aiRepliesEnabled(this)
        ) { enabled -> NotificationIntelligenceStore.setAiRepliesEnabled(this, enabled) }
        addButton("Reply language: ${NotificationIntelligenceStore.replyLanguage(this)}") { chooseReplyLanguage() }
        addButton("Custom reply template") { editReplyTemplate() }
        addButton("Preview custom reply") { previewReply() }
        addButton("Reply delay: ${NotificationIntelligenceStore.replyDelaySeconds(this)} seconds") { editReplyDelay() }
        addButton("Daily reply limit: ${NotificationIntelligenceStore.dailyReplyLimit(this)}") { editDailyLimit() }
        addButton("Quiet hours") { editQuietHours() }
        addButton("Excluded contacts / conversations") { editExclusions(contacts = true) }
        addButton("Excluded keywords") { editExclusions(contacts = false) }

        addText("NOTIFICATION RULES", 12f, bold = true)
        addText(
            "Rules are evaluated locally in priority order. Replies still require an enabled app, an Android notification reply action, the master switch, and available daily reply capacity.",
            13f
        )
        addButton("Create notification rule") { createRule() }
        addButton("View, test, or remove rules") { showRules() }

        addText("LOCAL NOTIFICATION DASHBOARD", 12f, bold = true)
        addButton("View notification history") { showNotificationHistory() }
        addButton("Clear notification history") { clearNotificationHistory() }
        addButton("View execution log (message bodies omitted)") { showExecutionLog() }

        addText("APP-SPECIFIC SETTINGS", 12f, bold = true)
        addText(
            "Use Per-app notification controls to select monitoring and announcements. Allow Auto-Reply only for supported messaging apps. Every automatic send also requires a matching, enabled AUTHORIZED_AUTO_REPLY rule; use SUGGEST_REPLY rules for preview/draft mode.",
            13f
        )

        addText(
            "Privacy: Notification history stays on this device for apps you select. If AI-generated replies are enabled, recent incoming messages from the conversation are sent over HTTPS to Gemini; otherwise replies use your local template. HOPE sends only through the message app's notification quick-reply action, skips sensitive credential/payment messages, and never opens chats or taps Send. Auto-replies stop at the configured daily limit and respect quiet hours and exclusions.",
            12f
        )
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            updatePermissionStatus()
        }
    }

    private fun addSettingSwitch(title: String, description: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = getDrawable(R.drawable.bg_jarvis_card)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        labels.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(themeColor(R.attr.themeTextMuted))
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = initial
            var restoring = false
            setOnCheckedChangeListener { _, checked ->
                if (restoring) return@setOnCheckedChangeListener
                try {
                    onChange(checked)
                    if (::replyStatus.isInitialized) updatePermissionStatus()
                } catch (error: Exception) {
                    restoring = true
                    isChecked = !checked
                    restoring = false
                    Toast.makeText(this@AutoReplySettingsActivity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        row.addView(toggle)
        root.addView(row, matchWidth())
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
    }

    private fun chooseReplyLanguage() {
        val choices = arrayOf("Auto", "English", "Hindi", "Hinglish")
        val selected = choices.indexOf(NotificationIntelligenceStore.replyLanguage(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Reply language")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                NotificationIntelligenceStore.setReplyLanguage(this, choices[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editReplyTemplate() {
        val input = EditText(this).apply {
            setText(NotificationIntelligenceStore.replyTemplate(this@AutoReplySettingsActivity))
            minLines = 2
            maxLines = 5
            hint = "Placeholders: {app_name}, {sender}, {message}, {time}"
        }
        AlertDialog.Builder(this)
            .setTitle("Custom reply template")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                try {
                    NotificationIntelligenceStore.setReplyTemplate(this, input.text.toString())
                } catch (error: Exception) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewReply() {
        val reply = NotificationRuleEngine.renderTemplate(
            NotificationIntelligenceStore.replyTemplate(this),
            "WhatsApp",
            "Aarav",
            "Are we still meeting today?",
            "18:30"
        )
        AlertDialog.Builder(this).setTitle("Custom reply preview").setMessage(reply)
            .setPositiveButton("Close", null).show()
    }

    private fun editReplyDelay() = showNumberInput(
        "Reply delay (seconds)", NotificationIntelligenceStore.replyDelaySeconds(this).toString(), 0..300
    ) { NotificationIntelligenceStore.setReplyDelaySeconds(this, it) }

    private fun editDailyLimit() = showNumberInput(
        "Daily reply limit", NotificationIntelligenceStore.dailyReplyLimit(this).toString(), 1..500
    ) { NotificationIntelligenceStore.setDailyReplyLimit(this, it) }

    private fun showNumberInput(title: String, current: String, range: IntRange, save: (Int) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in range) {
                    Toast.makeText(this, "Enter a value from ${range.first} to ${range.last}.", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        save(value)
                        recreate()
                    } catch (error: Exception) {
                        Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editQuietHours() {
        val (savedStart, savedEnd) = NotificationIntelligenceStore.quietHours(this)
        val start = savedStart ?: 22 * 60
        val end = savedEnd ?: 7 * 60
        AlertDialog.Builder(this)
            .setTitle("Quiet hours")
            .setItems(arrayOf("Set or update schedule", "Clear schedule")) { _, selected ->
                if (selected == 0) {
                    TimePickerDialog(this, { _, hour, minute ->
                        val startMinute = hour * 60 + minute
                        TimePickerDialog(this, { _, endHour, endMinute ->
                            try {
                                NotificationIntelligenceStore.setQuietHours(
                                    this, startMinute, endHour * 60 + endMinute
                                )
                                recreate()
                            } catch (error: Exception) {
                                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                            }
                        }, end / 60, end % 60, true).show()
                    }, start / 60, start % 60, true).show()
                } else {
                    NotificationIntelligenceStore.setQuietHours(this, null, null)
                    recreate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editExclusions(contacts: Boolean) {
        val stored = if (contacts) NotificationIntelligenceStore.excludedContacts(this)
        else NotificationIntelligenceStore.excludedKeywords(this)
        val input = EditText(this).apply { setText(stored.joinToString(", ")); hint = "Comma-separated values" }
        AlertDialog.Builder(this)
            .setTitle(if (contacts) "Excluded contacts and chats" else "Excluded keywords")
            .setMessage("Matches are excluded from announcements and auto-replies.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val values = input.text.toString().split(',').map(String::trim).filter(String::isNotBlank).toSet()
                if (contacts) NotificationIntelligenceStore.setExcludedContacts(this, values)
                else NotificationIntelligenceStore.setExcludedKeywords(this, values)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRule() {
        promptRuleField("Rule name", "") { name ->
            val sourceLabels = mutableListOf(
                "All supported apps",
                "WhatsApp",
                "WhatsApp Business",
                "Instagram"
            )
            val sourcePackages = mutableListOf(
                "*",
                "com.whatsapp",
                "com.whatsapp.w4b",
                "com.instagram.android"
            )
            android.provider.Telephony.Sms.getDefaultSmsPackage(this)?.let {
                sourceLabels.add("Default SMS app")
                sourcePackages.add(it)
            }
            AlertDialog.Builder(this).setTitle("Rule source")
                .setItems(sourceLabels.toTypedArray()) { _, sourceIndex ->
                    promptRuleField("Keyword filter (optional)", "") { keyword ->
                        promptRuleField("Sender/contact filter (optional)", "") { sender ->
                            val actions = NotificationRuleAction.entries
                            AlertDialog.Builder(this).setTitle("Rule action")
                                .setItems(arrayOf("Ignore", "Announce aloud", "Display", "Suggest reply", "Authorized auto-reply")) { _, actionIndex ->
                                    val action = actions[actionIndex]
                                    val replyTemplate = NotificationIntelligenceStore.replyTemplate(this)
                                    promptRuleField(
                                        if (action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY) {
                                            "Reply template: {app_name}, {sender}, {message}, {time}"
                                        } else {
                                            "Notification template: {app_name}, {sender}, {message}, {time}, {suggested_reply}"
                                        },
                                        if (action == NotificationRuleAction.AUTHORIZED_AUTO_REPLY) {
                                            replyTemplate
                                        } else {
                                            "{app_name} message from {sender}: {message}"
                                        }
                                    ) { template ->
                                        promptRuleField("Priority (0–1000; higher runs first)", "0") { priorityText ->
                                            val priority = priorityText.toIntOrNull()
                                            if (priority == null || priority !in 0..1000) {
                                                Toast.makeText(this, "Priority must be from 0 to 1000.", Toast.LENGTH_LONG).show()
                                            } else {
                                                try {
                                                    val rule = NotificationRule(
                                                        name = name,
                                                        packageName = sourcePackages[sourceIndex],
                                                        keyword = keyword,
                                                        sender = sender,
                                                        action = action,
                                                        template = template,
                                                        priority = priority
                                                    )
                                                    NotificationIntelligenceStore.saveRule(this, rule)
                                                    NotificationIntelligenceStore.addExecutionLog(this, "Created rule ${rule.name}")
                                                    Toast.makeText(this, "Rule saved.", Toast.LENGTH_SHORT).show()
                                                } catch (error: Exception) {
                                                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                }.show()
                        }
                    }
                }.show()
        }
    }

    private fun promptRuleField(title: String, initial: String, onValue: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); hint = title }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Next") { _, _ ->
                val value = input.text.toString().trim()
                if (title == "Rule name" && value.isBlank()) {
                    Toast.makeText(this, "Rule name is required.", Toast.LENGTH_SHORT).show()
                } else onValue(value)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showRules() {
        val rules = try {
            NotificationIntelligenceStore.rules(this)
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            return
        }
        if (rules.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Notification rules").setMessage("No rules created yet.")
                .setPositiveButton("Close", null).show()
            return
        }
        val ordered = rules.sortedWith(compareByDescending<NotificationRule> { it.priority }.thenBy { it.name })
        AlertDialog.Builder(this).setTitle("Rules — tap one")
            .setItems(ordered.map { "${it.name} • ${it.action.name} • ${it.keyword.ifBlank { "all" }}" }.toTypedArray()) { _, index ->
                val rule = ordered[index]
                AlertDialog.Builder(this)
                    .setTitle(rule.name)
                    .setItems(
                        arrayOf(
                            "Test rule",
                            if (rule.enabled) "Disable rule" else "Enable rule",
                            "Change priority (${rule.priority})",
                            "Remove rule"
                        )
                    ) { _, action ->
                        when (action) {
                            0 -> testRule(rule)
                            1 -> {
                                NotificationIntelligenceStore.saveRule(this, rule.copy(enabled = !rule.enabled))
                                NotificationIntelligenceStore.addExecutionLog(
                                    this,
                                    "${if (rule.enabled) "Disabled" else "Enabled"} rule ${rule.name}"
                                )
                            }
                            2 -> showNumberInput("Rule priority (0–1000)", rule.priority.toString(), 0..1000) {
                                NotificationIntelligenceStore.saveRule(this, rule.copy(priority = it))
                                NotificationIntelligenceStore.addExecutionLog(this, "Updated priority for ${rule.name}")
                            }
                            3 -> {
                                NotificationIntelligenceStore.removeRule(this, rule.id)
                                NotificationIntelligenceStore.addExecutionLog(this, "Removed rule ${rule.name}")
                            }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun testRule(rule: NotificationRule) {
        val input = NotificationRuleInput(
            rule.packageName.takeUnless { it == "*" } ?: "com.whatsapp",
            "Aarav",
            "Are we still meeting today?"
        )
        val matched = NotificationRuleEngine.matchingRules(input, listOf(rule)).isNotEmpty()
        val result = if (!matched) "This rule does not match the sample notification." else
            NotificationRuleEngine.renderTemplate(
                rule.template.ifBlank { "{app_name} from {sender}: {message}" },
                "WhatsApp", input.sender, input.message, "18:30", "Thanks, I will reply soon."
            )
        NotificationIntelligenceStore.addExecutionLog(this, "Tested rule ${rule.name}: ${if (matched) "matched" else "no match"}")
        AlertDialog.Builder(this).setTitle("Rule test").setMessage(result)
            .setPositiveButton("Close", null).show()
    }

    private fun showNotificationHistory() {
        val content = NotificationIntelligenceStore.history(this).take(30).joinToString("\n\n") { record ->
            "${record.formattedTime} • ${record.appName} • ${record.sender}\n" +
                if (record.sensitive) "[Sensitive notification]" else record.message
        }.ifBlank { "No notifications recorded. Enable Read Notifications and select apps." }
        AlertDialog.Builder(this).setTitle("Local notification history").setMessage(content)
            .setPositiveButton("Close", null).show()
    }

    private fun clearNotificationHistory() {
        AlertDialog.Builder(this).setTitle("Clear notification history?")
            .setMessage("Stored notification messages will be removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                NotificationIntelligenceStore.clearHistory(this)
                Toast.makeText(this, "History cleared.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun chooseHistoryRetention() {
        val days = intArrayOf(1, 7, 30, 90, 180, 365)
        val labels = days.map { "$it days" }.toTypedArray()
        val checked = days.indexOf(NotificationIntelligenceStore.historyRetentionDays(this))
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Notification history retention")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                try {
                    NotificationIntelligenceStore.setHistoryRetentionDays(this, days[which])
                    dialog.dismiss()
                    recreate()
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "Could not update notification history retention.", error)
                    Toast.makeText(
                        this,
                        "Could not update notification history retention: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExecutionLog() {
        val content = NotificationIntelligenceStore.executionLog(this).take(50).joinToString("\n")
            .ifBlank { "No notification actions logged." }
        AlertDialog.Builder(this).setTitle("Execution log (message bodies omitted)")
            .setMessage(content)
            .setNeutralButton("Clear") { _, _ -> NotificationIntelligenceStore.clearExecutionLog(this) }
            .setPositiveButton("Close", null).show()
    }

    private fun updatePermissionStatus() {
        val enabled = PermissionHelper.isNotificationListenerEnabled(this)
        serviceStatus.text = if (enabled) {
            "Android Notification Access: granted • listener: " +
                if (NotificationIntelligenceStore.isListenerConnected(this)) "connected" else "not connected"
        } else {
            "Android Notification Access: not granted. Grant access to receive delivered notifications."
        }
        serviceStatus.setTextColor(themeColor(if (enabled) R.attr.themeAccent else R.attr.themeTextMuted))
        val notificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHelper.hasNotificationPermission(this)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
        }
        notificationStatus.text = if (notificationsEnabled) {
            "HOPE notifications: enabled"
        } else {
            "HOPE notifications: disabled. Enable them to see auto-reply status."
        }
        notificationStatus.setTextColor(themeColor(if (notificationsEnabled) R.attr.themeAccent else R.attr.themeTextMuted))
        val masterEnabled = NotificationIntelligenceStore.isMasterEnabled(this)
        val monitoringEnabled = NotificationIntelligenceStore.isReadingEnabled(this)
        val monitoredAppCount = NotificationIntelligenceStore.monitoredApps(this).size
        val enabledApps = listOf(
            AutoReplyCoordinator.WHATSAPP_SETTING_KEY,
            AutoReplyCoordinator.INSTAGRAM_SETTING_KEY,
            AutoReplyCoordinator.SMS_SETTING_KEY
        ).count { AutoReplyPreferences.isAppEnabled(this, it) }
        val paused = NotificationIntelligenceStore.isPaused(this)
        val lastNotificationAt = NotificationIntelligenceStore.lastNotificationAt(this)
        val lastReceived = if (lastNotificationAt > 0L) {
            android.text.format.DateFormat.format("MMM d, HH:mm", lastNotificationAt)
        } else "none received"
        serviceStatus.text = "${serviceStatus.text}\nLast notification: $lastReceived\n${HopeSpeech.status(this)}"
        replyStatus.text = when {
            !enabled -> "Replies are paused: grant HOPE notification access."
            !masterEnabled -> "Replies are off: turn on the AI Auto Reply master switch."
            enabledApps == 0 -> "Replies are off: enable at least one app below."
            !monitoringEnabled || monitoredAppCount == 0 ->
                "Replies are paused: enable global monitoring and monitor the selected app in Per-app notification controls."
            paused -> "Replies are paused. Resume notification intelligence to continue."
            else -> "Monitoring is ready for $enabledApps selected app(s). Automatic sends still require an enabled AUTHORIZED_AUTO_REPLY rule, a supported notification RemoteInput, and all safety checks."
        }
        replyStatus.setTextColor(
            themeColor(
                if (enabled && masterEnabled && monitoringEnabled && monitoredAppCount > 0 &&
                    enabledApps > 0 && !paused
                ) {
                    R.attr.themeAccent
                } else {
                    R.attr.themeTextMuted
                }
            )
        )
    }

    private fun addText(textValue: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(themeColor(R.attr.themeTextPrimary))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
            root.addView(this, matchWidth())
        }

    private fun addButton(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }, matchWidth())
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "AutoReplySettings"
    }
}
