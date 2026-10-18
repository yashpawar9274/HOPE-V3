package com.jarvis.assistant.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.autoreply.AutoReplyCoordinator
import com.jarvis.assistant.autoreply.NotificationInboxEvents
import com.jarvis.assistant.autoreply.NotificationIntelligenceStore
import com.jarvis.assistant.autoreply.NotificationRecord
import com.jarvis.assistant.telephony.IncomingCallPreferences
import com.jarvis.assistant.util.HopeSpeech
import com.jarvis.assistant.util.ThemeManager

class NotificationInboxActivity : AppCompatActivity() {
    private lateinit var entries: LinearLayout
    private lateinit var status: TextView
    private var receiverRegistered = false

    private val inboxChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationInboxEvents.ACTION_CHANGED) refreshInbox()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(20) + bars.bottom)
            insets
        }
        ThemeManager.applyDynamicBackground(root)
        root.addView(TextView(this).apply {
            text = "NOTIFICATION INBOX"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        }, matchWidth())
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(7), 0, dp(7))
        }
        root.addView(status, matchWidth())
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(actionButton("Mark all read") {
            runCatching { NotificationIntelligenceStore.markAllNotificationsRead(this) }
                .onSuccess { refreshInbox() }
                .onFailure { showError("Could not mark notifications as read.", it) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        controls.addView(actionButton("Clear history") { confirmClear() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(controls, matchWidth())
        val scroll = ScrollView(this).apply { isFillViewport = true }
        entries = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        scroll.addView(entries, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshInbox()
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
        refreshInbox()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(inboxChanges)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun refreshInbox() {
        if (!::entries.isInitialized) return
        val history = try {
            NotificationIntelligenceStore.history(this)
        } catch (error: Exception) {
            showError("Could not load the local notification inbox.", error)
            emptyList()
        }
        val unread = history.count { !it.isRead && !it.dismissed }
        status.text = when {
            history.isEmpty() -> "No received notifications are stored. Grant Notification Access, enable monitoring, and select apps in Notification Settings."
            else -> "${history.size} stored • $unread unread • Retained for ${NotificationIntelligenceStore.historyRetentionDays(this)} days"
        }
        entries.removeAllViews()
        if (history.isEmpty()) {
            entries.addView(TextView(this).apply {
                text = "Inbox is empty"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(themeColor(R.attr.themeTextMuted))
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }, matchWidth())
            return
        }
        history.forEach { record -> entries.addView(notificationCard(record), matchWidth()) }
    }

    private fun notificationCard(record: NotificationRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ContextCompat.getDrawable(this@NotificationInboxActivity, R.drawable.bg_jarvis_card)
            alpha = if (record.isRead || record.dismissed) 0.78f else 1f
            setOnClickListener { openRecord(record) }
        }
        val icon = ImageView(this).apply {
            setImageDrawable(appIcon(record.packageName))
            contentDescription = "${record.appName} icon"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = listOf(record.appName, record.sender.takeIf(String::isNotBlank))
            .filterNotNull().joinToString(" • ")
        details.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        }, matchWidth())
        details.addView(TextView(this).apply {
            text = when {
                record.sensitive -> "[Sensitive notification hidden]"
                record.message.isBlank() -> "No notification text was provided"
                else -> record.message
            }
            textSize = 14f
            maxLines = 4
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(4), 0, dp(4))
        }, matchWidth())
        val state = when {
            record.dismissed -> "Dismissed"
            record.isRead -> "Read"
            else -> "Unread"
        }
        details.addView(TextView(this).apply {
            text = "${record.formattedTime} • $state"
            textSize = 11f
            setTextColor(themeColor(if (record.isRead) R.attr.themeTextMuted else R.attr.themeAccent))
        }, matchWidth())
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        return card
    }

    private fun openRecord(record: NotificationRecord) {
        if (!record.isRead) {
            try {
                NotificationIntelligenceStore.markNotificationRead(this, record.id)
            } catch (error: Exception) {
                showError("Could not update notification read status.", error)
            }
        }
        refreshInbox()
        val body = if (record.sensitive) {
            AlertDialog.Builder(this)
                .setTitle("Sensitive notification")
                .setMessage("This notification may contain an OTP, financial, or security-related message. Reveal it?")
                .setNegativeButton("Keep hidden", null)
                .setPositiveButton("Reveal") { _, _ -> showNotificationDetails(record, record.message) }
                .show()
            return
        } else record.message.ifBlank { "This notification contained no readable message text." }
        showNotificationDetails(record, body)
    }

    private fun showNotificationDetails(record: NotificationRecord, message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("${record.appName} • ${record.sender.ifBlank { "Notification" }}")
            .setMessage("${record.formattedTime}\n\n$message")
            .setPositiveButton("Close", null)
        if (!record.sensitive && record.message.isNotBlank()) {
            dialog.setNeutralButton("Preview spoken reply") { _, _ -> previewSpokenReply(record) }
        }
        dialog.show()
    }

    private fun previewSpokenReply(record: NotificationRecord) {
        val blockReason = HopeSpeech.announcementBlockReason(
            this,
            NotificationIntelligenceStore.announcementsHeadphonesOnly(this)
        )
        if (blockReason != null) {
            android.widget.Toast.makeText(this, blockReason, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.widget.Toast.makeText(this, "Preparing a reply preview…", android.widget.Toast.LENGTH_SHORT).show()
        AutoReplyCoordinator.generateSpokenReplySuggestion(
            this,
            record.packageName,
            record.sender,
            record.message
        ) { result ->
            if (isFinishing || isDestroyed) return@generateSpokenReplySuggestion
            result.onSuccess { suggestion ->
                AlertDialog.Builder(this)
                    .setTitle("HOPE reply preview")
                    .setMessage("$suggestion\n\nThis only plays the preview aloud; it does not send a message.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Speak aloud") { _, _ ->
                        speakReplySuggestion(suggestion)
                    }
                    .show()
            }.onFailure { error ->
                android.util.Log.e(TAG, "Could not prepare a spoken reply preview.", error)
                android.widget.Toast.makeText(
                    this,
                    error.message ?: "Could not generate a reply preview.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun speakReplySuggestion(suggestion: String) {
        val blockReason = HopeSpeech.announcementBlockReason(
            this,
            NotificationIntelligenceStore.announcementsHeadphonesOnly(this)
        )
        if (blockReason != null) {
            android.widget.Toast.makeText(this, blockReason, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val accepted = HopeSpeech.speak(
            this,
            suggestion,
            IncomingCallPreferences.language(this),
            "hope-spoken-reply-${java.util.UUID.randomUUID()}",
            requireAudioFocus = true
        ) { completed ->
            val status = if (completed) "HOPE reply preview spoken." else "HOPE could not finish speaking the reply preview."
            try {
                NotificationIntelligenceStore.addExecutionLog(this, status)
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Could not update spoken reply diagnostics.", error)
            }
            runOnUiThread {
                android.widget.Toast.makeText(this, status, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (!accepted) {
            android.widget.Toast.makeText(this, HopeSpeech.status(this), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun appIcon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
        android.util.Log.w(TAG, "Notification source app icon is no longer installed.")
        null
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear notification history?")
            .setMessage("Stored notification text will be removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                try {
                    NotificationIntelligenceStore.clearHistory(this)
                    refreshInbox()
                } catch (error: Exception) {
                    showError("Could not clear notification history.", error)
                }
            }
            .show()
    }

    private fun actionButton(label: String, action: () -> Unit) =
        android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun showError(message: String, error: Throwable) {
        android.util.Log.e(TAG, message, error)
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "NotificationInbox"
    }
}
