package com.jarvis.assistant.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Telephony
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.autoreply.AutoReplyCoordinator
import com.jarvis.assistant.autoreply.AutoReplyPolicy
import com.jarvis.assistant.autoreply.AutoReplyPreferences
import com.jarvis.assistant.autoreply.NotificationInboxEvents
import com.jarvis.assistant.autoreply.NotificationIntelligenceStore
import com.jarvis.assistant.util.HopeSpeech
import com.jarvis.assistant.util.ThemeManager

class NotificationAppsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var appRows: LinearLayout
    private lateinit var listenerStatus: TextView
    private var receiverRegistered = false

    private val inboxChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationInboxEvents.ACTION_CHANGED) {
                renderApps()
                updateDiagnostics()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(18) + bars.left, dp(14) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(root)

        addText("NOTIFICATION SOURCES", 22f, true)
        addText(
            "Apps appear here only after Android has delivered a notification from them, or if they are installed and launchable. HOPE does not create inbox entries for notifications it has not received.",
            13f
        )
        listenerStatus = addText("", 13f, true)
        addButton("Grant / check Android Notification Access") {
            try {
                com.jarvis.assistant.util.PermissionHelper.openNotificationListenerSettings(this)
            } catch (error: Exception) {
                showError("Could not open Notification Access settings.", error)
            }
        }
        addSettingSwitch(
            "Monitor selected apps",
            "Master switch for local inbox capture, per-app announcements, and notification rules.",
            NotificationIntelligenceStore.isReadingEnabled(this)
        ) { NotificationIntelligenceStore.setReadingEnabled(this, it) }
        addSettingSwitch(
            "Pause notification intelligence",
            "Pause inbox processing, announcements, rules, and auto-replies.",
            NotificationIntelligenceStore.isPaused(this)
        ) { NotificationIntelligenceStore.setPaused(this, it) }
        addSettingSwitch(
            "Enable voice announcements",
            "The per-app Announce switch must also be on. Sensitive notifications stay muted by default.",
            NotificationIntelligenceStore.areAnnouncementsEnabled(this)
        ) { NotificationIntelligenceStore.setAnnouncementsEnabled(this, it) }
        addSettingSwitch(
            "Read message content aloud",
            "Off by default. When off, HOPE announces the app and sender only.",
            NotificationIntelligenceStore.announcesMessageContent(this)
        ) { NotificationIntelligenceStore.setAnnouncesMessageContent(this, it) }
        addSettingSwitch(
            "Headphones only",
            "Do not speak notification announcements through the phone speaker.",
            NotificationIntelligenceStore.announcementsHeadphonesOnly(this)
        ) { NotificationIntelligenceStore.setAnnouncementsHeadphonesOnly(this, it) }
        addSettingSwitch(
            "Allow sensitive notifications to be announced",
            "Off by default. OTP, financial, and security-related messages stay silent unless explicitly enabled.",
            NotificationIntelligenceStore.canAnnounceSensitive(this)
        ) { NotificationIntelligenceStore.setAnnounceSensitive(this, it) }
        addButton("Test HOPE announcement") { testAnnouncement() }
        addText(
            "Speech engine: ${HopeSpeech.status(this)}\n${NotificationIntelligenceStore.speechStatus(this)}",
            12f
        )
        addButton("Open notification inbox") {
            startActivity(Intent(this, NotificationInboxActivity::class.java))
        }
        addText("APP CONTROLS", 13f, true)
        appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appRows, matchWidth())
        renderApps()
        updateDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                inboxChanges,
                IntentFilter(NotificationInboxEvents.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        updateDiagnostics()
        renderApps()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(inboxChanges)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun renderApps() {
        if (!::appRows.isInitialized) return
        appRows.removeAllViews()
        val seen = NotificationIntelligenceStore.observedApps(this)
        val installed = try {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { app -> app.packageName != packageName && app.packageName !in seen }
                .filter { app ->
                    app.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                        packageManager.getLaunchIntentForPackage(app.packageName) != null
                }
                .toList()
        } catch (error: Exception) {
            showError("Could not load installed notification apps.", error)
            emptyList()
        }
        val observedApps = seen.mapNotNull { packageName ->
            try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                android.util.Log.i(TAG, "Observed notification source has been uninstalled.")
                null
            }
        }
        val apps = (observedApps + installed)
            .distinctBy(ApplicationInfo::packageName)
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        if (apps.isEmpty()) {
            addText("No notification apps have been seen yet. Keep this page enabled, then receive a notification; Android will add its app here.", 13f)
            return
        }
        apps.forEach { appInfo ->
            appRows.addView(appCard(appInfo, appInfo.packageName in seen), matchWidth())
        }
    }

    private fun appCard(appInfo: ApplicationInfo, observed: Boolean): LinearLayout {
        val appPackage = appInfo.packageName
        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(8), dp(10))
            background = ContextCompat.getDrawable(this@NotificationAppsActivity, R.drawable.bg_jarvis_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val icon = ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(appInfo))
            contentDescription = "$appName icon"
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply {
            text = "$appName${if (observed) " • notification received" else ""}"
            textSize = 15f
            setTextColor(themeColor(R.attr.themeTextPrimary))
        }, matchWidth())
        controls.addView(switchRow(
            "Monitor notifications",
            NotificationIntelligenceStore.isAppReadingEnabled(this, appPackage)
        ) { NotificationIntelligenceStore.setAppReadingEnabled(this, appPackage, it) })
        controls.addView(switchRow(
            "Announce aloud",
            NotificationIntelligenceStore.isAnnouncementAppEnabled(this, appPackage),
            enabled = AutoReplyPolicy.isSupportedPackage(appPackage, defaultSmsPackage()) ||
                NotificationIntelligenceStore.isAppReadingEnabled(this, appPackage)
        ) { NotificationIntelligenceStore.setAnnouncementAppEnabled(this, appPackage, it) })
        if (AutoReplyPolicy.isSupportedPackage(appPackage, defaultSmsPackage())) {
            val replyKey = replyPreferenceKey(appPackage)
            controls.addView(switchRow(
                "Allow automatic sending (requires an enabled auto-reply rule)",
                AutoReplyPreferences.isAppEnabled(this, replyKey)
            ) { AutoReplyPreferences.setAppEnabled(this, replyKey, it) })
        } else {
            controls.addView(TextView(this).apply {
                text = "Auto-reply unavailable: this app is not supported or does not expose an allowed messaging action."
                textSize = 11f
                setTextColor(themeColor(R.attr.themeTextMuted))
                setPadding(0, dp(4), 0, 0)
            }, matchWidth())
        }
        card.addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    private fun replyPreferenceKey(appPackage: String): String = when {
        appPackage == "com.whatsapp" || appPackage == "com.whatsapp.w4b" ->
            AutoReplyCoordinator.WHATSAPP_SETTING_KEY
        appPackage == "com.instagram.android" -> AutoReplyCoordinator.INSTAGRAM_SETTING_KEY
        else -> AutoReplyCoordinator.SMS_SETTING_KEY
    }

    private fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(this)
    } catch (error: Exception) {
        android.util.Log.w(TAG, "Could not determine the device default SMS app.", error)
        null
    }

    private fun switchRow(
        title: String,
        initial: Boolean,
        enabled: Boolean = true,
        save: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(themeColor(R.attr.themeTextMuted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(SwitchCompat(this).apply {
            isChecked = initial
            isEnabled = enabled
            var restoring = false
            setOnCheckedChangeListener { _, checked ->
                if (restoring) return@setOnCheckedChangeListener
                try {
                    save(checked)
                    updateDiagnostics()
                } catch (error: Exception) {
                    restoring = true
                    isChecked = !checked
                    restoring = false
                    showError("Could not save notification app settings.", error)
                }
            }
        })
        return row
    }

    private fun updateDiagnostics() {
        if (!::listenerStatus.isInitialized) return
        val permission = com.jarvis.assistant.util.PermissionHelper.isNotificationListenerEnabled(this)
        val last = NotificationIntelligenceStore.lastNotificationAt(this)
        val lastText = if (last > 0L) {
            android.text.format.DateFormat.format("MMM d, HH:mm", last)
        } else "none received yet"
        val selectedApps = NotificationIntelligenceStore.monitoredApps(this).map { appPackage ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(appPackage, 0)).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                appPackage
            }
        }.sorted()
        listenerStatus.text = buildString {
            append("Android Notification Access: ")
            append(if (permission) "granted" else "not granted")
            append(" • listener: ")
            append(if (NotificationIntelligenceStore.isListenerConnected(this@NotificationAppsActivity)) "connected" else "not connected")
            append(" • last notification: ")
            append(lastText)
            append("\nMonitoring selected apps: ")
            append(selectedApps.takeIf { it.isNotEmpty() }?.joinToString() ?: "none")
            append("\nSpeech engine: ")
            append(HopeSpeech.status(this@NotificationAppsActivity))
            append("\nAnnouncement status: ")
            append(NotificationIntelligenceStore.speechStatus(this@NotificationAppsActivity))
        }
        listenerStatus.setTextColor(themeColor(if (permission) R.attr.themeAccent else R.attr.themeTextMuted))
    }

    private fun testAnnouncement() {
        val blockReason = HopeSpeech.announcementBlockReason(
            this,
            NotificationIntelligenceStore.announcementsHeadphonesOnly(this)
        )
        if (blockReason != null) {
            Toast.makeText(this, blockReason, Toast.LENGTH_LONG).show()
            return
        }
        val accepted = HopeSpeech.speak(
            this,
            "HOPE here. This is a test announcement.",
            com.jarvis.assistant.telephony.IncomingCallPreferences.language(this),
            "hope-notification-test-${System.currentTimeMillis()}",
            requireAudioFocus = true
        ) { completed ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (completed) "HOPE test announcement played." else "HOPE speech did not finish. Check the speech engine status.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (!accepted) Toast.makeText(this, HopeSpeech.status(this), Toast.LENGTH_LONG).show()
    }

    private fun addText(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextPrimary))
            setPadding(0, dp(7), 0, dp(7))
            root.addView(this, matchWidth())
        }

    private fun addButton(label: String, action: () -> Unit) {
        root.addView(android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, matchWidth())
    }

    private fun addSettingSwitch(title: String, description: String, initial: Boolean, save: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(this@NotificationAppsActivity, R.drawable.bg_jarvis_card)
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        copy.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(themeColor(R.attr.themeTextMuted))
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(SwitchCompat(this).apply {
            isChecked = initial
            var restoring = false
            setOnCheckedChangeListener { _, checked ->
                if (restoring) return@setOnCheckedChangeListener
                try {
                    save(checked)
                    updateDiagnostics()
                } catch (error: Exception) {
                    restoring = true
                    isChecked = !checked
                    restoring = false
                    showError("Could not save notification settings.", error)
                }
            }
        })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
        root.addView(row)
    }

    private fun showError(message: String, error: Throwable) {
        android.util.Log.e(TAG, message, error)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "NotificationApps"
    }
}
