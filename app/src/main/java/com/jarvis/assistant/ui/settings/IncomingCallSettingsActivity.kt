package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.telephony.IncomingCallPreferences
import com.jarvis.assistant.telephony.PhoneRoleHelper
import com.jarvis.assistant.util.ThemeManager

class IncomingCallSettingsActivity : AppCompatActivity() {
    private lateinit var roleStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var voiceSwitch: SwitchCompat

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateRoleStatus()
        if (!PhoneRoleHelper.isDefaultPhoneApp(this)) {
            Toast.makeText(this, "HOPE is not the default phone app. Android will keep call handling with the current phone app.", Toast.LENGTH_LONG).show()
        }
    }

    private val micPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            IncomingCallPreferences.setVoiceConfirmationEnabled(this, true)
            voiceSwitch.isChecked = true
        } else {
            voiceSwitch.isChecked = false
            IncomingCallPreferences.setVoiceConfirmationEnabled(this, false)
            Toast.makeText(this, "Microphone permission was denied. Answer and Reject buttons remain available.", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Enable HOPE notifications in Android settings to show incoming-call controls.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(root)

        addText("INCOMING CALL ANNOUNCEMENT", 22f, true)
        addText(
            "HOPE can announce callers and notifications using the same installed Android speech engine and language. It can also ask whether you want to answer or reject; silence or an unclear command leaves the call ringing. Making HOPE the default phone app replaces the current call interface; you can restore your previous phone app in Android settings.",
            14f
        )
        roleStatus = addText("", 14f, true)
        addButton("Set HOPE as default phone app") {
            val requestIntent = PhoneRoleHelper.requestDefaultPhoneApp(this)
            if (requestIntent == null) {
                Toast.makeText(this, "This device does not offer the Android default-phone-app role.", Toast.LENGTH_LONG).show()
            } else {
                try {
                    roleRequest.launch(requestIntent)
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not open Android's default phone app prompt: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        addButton("Allow incoming call screen and notifications") {
            openCallNotificationSettings()
        }
        addSettingSwitch(
            "Announce incoming callers",
            "Uses the shared Android HOPE announcement voice. Caller name/number is spoken only when Android provides it.",
            IncomingCallPreferences.isAnnouncementEnabled(this)
        ) { enabled -> IncomingCallPreferences.setAnnouncementEnabled(this, enabled) }
        voiceSwitch = addSettingSwitch(
            "Ask for spoken answer/reject command",
            "After HOPE asks, voice recognition briefly listens for a clear command. If no command is recognized, the call is not changed.",
            IncomingCallPreferences.isVoiceConfirmationEnabled(this) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { enabled ->
            if (enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                IncomingCallPreferences.setVoiceConfirmationEnabled(this, enabled)
            }
        }
        addButton("Call and notification announcement language: ${IncomingCallPreferences.language(this)}") {
            chooseLanguage()
        }
        addText(
            "Privacy: voice commands use Android's installed speech-recognition service; offline recognition is requested when supported but is not guaranteed. HOPE does not record or analyze call audio. If microphone permission, recognition, or the default-phone-app role is unavailable, use Android's on-screen call controls.",
            12f
        )
        updateRoleStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::roleStatus.isInitialized) updateRoleStatus()
    }

    private fun updateRoleStatus() {
        roleStatus.text = if (PhoneRoleHelper.isDefaultPhoneApp(this)) {
            "Phone app status: HOPE is the default phone app."
        } else {
            "Phone app status: HOPE is not the default phone app. Caller announcements and call controls will remain with your current phone app until you approve the Android role."
        }
        roleStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (PhoneRoleHelper.isDefaultPhoneApp(this)) R.color.status_green else R.color.status_amber
            )
        )
    }

    private fun chooseLanguage() {
        val languages = arrayOf("Auto", "English", "Hindi")
        val current = languages.indexOf(IncomingCallPreferences.language(this)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Incoming-call language")
            .setSingleChoiceItems(languages, current) { dialog, which ->
                IncomingCallPreferences.setLanguage(this, languages[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCallNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                notificationManager?.canUseFullScreenIntent() == false
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
                return
            }
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open notification settings: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addSettingSwitch(
        title: String,
        description: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchCompat {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = getDrawable(R.drawable.bg_jarvis_card)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        labels.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(themeColor(R.attr.themeTextMuted))
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = initial
            var reverting = false
            setOnCheckedChangeListener { _, checked ->
                if (reverting) return@setOnCheckedChangeListener
                try {
                    onChange(checked)
                } catch (error: Exception) {
                    reverting = true
                    isChecked = !checked
                    reverting = false
                    Toast.makeText(this@IncomingCallSettingsActivity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        row.addView(toggle)
        root.addView(row, matchWidth())
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(8)))
        return toggle
    }

    private fun addText(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(if (size >= 18f) R.attr.themeTextPrimary else R.attr.themeTextMuted))
            setPadding(0, dp(8), 0, dp(8))
            root.addView(this, matchWidth())
        }

    private fun addButton(text: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }, matchWidth())
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
