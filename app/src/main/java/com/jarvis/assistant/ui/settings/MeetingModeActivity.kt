package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.autoreply.NotificationIntelligenceStore
import com.jarvis.assistant.meeting.MeetingCallScreener
import com.jarvis.assistant.meeting.MeetingModeLogic
import com.jarvis.assistant.meeting.MeetingModePreferences
import com.jarvis.assistant.telephony.PhoneRoleHelper
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager

/**
 * Settings for HOPE Meeting Mode: on/off switch, situation (meeting / sleeping / busy), how long,
 * the owner's own prompt that tells HOPE how to talk to callers, setup status and a short log.
 */
class MeetingModeActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var pendingActivate = false
    private lateinit var promptField: EditText

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Without SMS permission HOPE can only decline calls, not text the caller.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (pendingActivate) {
            pendingActivate = false
            turnOn()
        } else {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(36))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(16) + bars.left, dp(18) + bars.top, dp(16) + bars.right, dp(36) + bars.bottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun turnOn() {
        try {
            MeetingModePreferences.activate(
                this,
                MeetingModePreferences.situation(this),
                MeetingModePreferences.durationMinutes(this)
            )
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not turn Meeting Mode on.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun savePrompt() {
        if (::promptField.isInitialized) {
            MeetingModePreferences.setCustomPrompt(this, promptField.text.toString())
        }
    }

    private fun render() {
        savePrompt()
        root.removeAllViews()
        val active = MeetingModePreferences.isActive(this)

        text("Meeting Mode", 34f, bold = true, color = themeColor(R.attr.themeTextPrimary), pad = intArrayOf(4, 8, 4, 2))
        text(
            "When you are in a meeting or sleeping, HOPE declines calls on your own SIM, texts the caller, and keeps chatting with them for you.",
            14f,
            color = themeColor(R.attr.themeTextMuted),
            pad = intArrayOf(4, 0, 4, 14)
        )

        // Hero switch
        val hero = card(20, 16)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label("HOPE Meeting Mode", 19f, bold = true))
        labels.addView(
            label(
                if (active) {
                    val until = MeetingModePreferences.untilText(this)
                    "On \u2022 ${MeetingModePreferences.situation(this).label}" +
                        if (until.isNotEmpty()) " until $until" else " until you switch it off"
                } else "Off",
                12f,
                muted = true
            )
        )
        head.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = iosSwitch(active)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!MeetingCallScreener.canSendSms(this)) {
                    pendingActivate = true
                    smsPermission.launch(Manifest.permission.SEND_SMS)
                } else {
                    turnOn()
                }
            } else {
                MeetingModePreferences.deactivate(this)
                render()
            }
        }
        head.addView(toggle)
        hero.addView(head, matchWidth())
        hero.addView(
            label(
                "Calls are declined without ringing. HOPE then texts: who she is, that you are busy, and asks what it is about. " +
                    "A second call within 5 minutes still rings, so real emergencies get through.",
                13f,
                secondary = true
            ).apply { setPadding(0, dp(12), 0, 0) },
            matchWidth()
        )
        root.addView(hero, matchWidth().apply { bottomMargin = dp(8) })

        header("I AM")
        segmented(
            MeetingModeLogic.Situation.values().map { it.label },
            MeetingModeLogic.Situation.values().indexOf(MeetingModePreferences.situation(this))
        ) { index ->
            val chosen = MeetingModeLogic.Situation.values()[index]
            MeetingModePreferences.setSituation(this, chosen)
            if (MeetingModePreferences.isActive(this)) {
                MeetingModePreferences.activate(this, chosen, MeetingModePreferences.durationMinutes(this))
            }
            render()
        }

        header("FOR")
        val durations = listOf("30 min" to 30, "1 hour" to 60, "2 hours" to 120, "Until off" to 0)
        segmented(durations.map { it.first }, durations.indexOfFirst { it.second == MeetingModePreferences.durationMinutes(this) }) { index ->
            MeetingModePreferences.setDurationMinutes(this, durations[index].second)
            if (MeetingModePreferences.isActive(this)) {
                MeetingModePreferences.activate(this, MeetingModePreferences.situation(this), durations[index].second)
            }
            render()
        }

        header("LET THESE CALLS RING")
        val options = group()
        switchRow(
            options,
            "Starred contacts",
            "Contacts you marked as favourite always ring.",
            MeetingModePreferences.allowStarred(this)
        ) { MeetingModePreferences.setAllowStarred(this, it) }
        switchRow(
            options,
            "Repeated calls",
            "If the same person calls again within 5 minutes, it rings.",
            MeetingModePreferences.allowRepeated(this)
        ) { MeetingModePreferences.setAllowRepeated(this, it) }

        header("HOW HOPE SHOULD TALK (OPTIONAL)")
        promptField = EditText(this).apply {
            setText(MeetingModePreferences.customPrompt(this@MeetingModeActivity))
            hint = "Example: Be polite and formal. Ask the caller's name, company and reason. " +
                "If it is about delivery, tell them to leave it with the guard."
            setHintTextColor(themeColor(R.attr.themeTextMuted))
            setTextColor(themeColor(R.attr.themeTextPrimary))
            textSize = 14f
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(themeColor(R.attr.themeCardBg), 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(promptField, matchWidth())
        root.addView(
            label(
                "Your instructions are added to HOPE's prompt for the SMS and for every reply while Meeting Mode is on. Max ${MeetingModeLogic.MAX_CUSTOM_PROMPT_CHARS} characters.",
                12f,
                muted = true
            ).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) },
            matchWidth()
        )
        root.addView(button("Save instructions") {
            savePrompt()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, matchWidth())

        header("SETUP")
        val setup = group()
        statusRow(
            setup,
            "HOPE is the default Phone app",
            PhoneRoleHelper.isDefaultPhoneApp(this),
            "Needed to decline calls"
        ) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (error: Exception) {
                Toast.makeText(this, "Open Settings > Apps > Default apps > Phone app.", Toast.LENGTH_LONG).show()
            }
        }
        statusRow(setup, "SMS permission", MeetingCallScreener.canSendSms(this), "Needed to text callers") {
            smsPermission.launch(Manifest.permission.SEND_SMS)
        }
        statusRow(
            setup,
            "Notification access",
            PermissionHelper.isNotificationListenerEnabled(this),
            "Needed to chat with callers who reply"
        ) {
            try {
                PermissionHelper.openNotificationListenerSettings(this)
            } catch (error: Exception) {
                Toast.makeText(this, "Could not open notification access settings.", Toast.LENGTH_LONG).show()
            }
        }
        statusRow(
            setup,
            "Contacts permission",
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            "Lets HOPE use names and starred contacts"
        ) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }

        header("RECENT ACTIVITY")
        val log = MeetingModePreferences.log(this).take(15)
        if (log.isEmpty()) {
            root.addView(label("Nothing yet.", 13f, muted = true).apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }, matchWidth())
        } else {
            val entries = group()
            log.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val time = android.text.format.DateFormat.getTimeFormat(this).format(entry.time)
                row.addView(label("${entry.title} \u2022 $time", 14f, bold = true))
                if (entry.detail.isNotBlank()) row.addView(label(entry.detail, 12f, muted = true))
                addRow(entries, row)
            }
            root.addView(button("Clear activity") {
                MeetingModePreferences.clearLog(this)
                render()
            }, matchWidth())
        }

        root.addView(
            label(
                "Good to know: Android does not let any app hear or speak inside a normal SIM call, so HOPE cannot talk on the call itself. " +
                    "She answers by text instead (SMS now, and WhatsApp/SMS replies through Smart Auto-Reply). " +
                    "Replies are sent from the SIM your phone uses for SMS by default.",
                12f,
                muted = true
            ).apply { setPadding(dp(8), dp(18), dp(8), dp(8)) },
            matchWidth()
        )
    }

    // ---- small UI helpers -------------------------------------------------------------

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card(radiusDp: Int, padDp: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp + 2), dp(padDp), dp(padDp + 2), dp(padDp))
        background = rounded(themeColor(R.attr.themeCardBg), radiusDp)
    }

    private fun group(): LinearLayout {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(themeColor(R.attr.themeCardBg), 14)
        }
        root.addView(g, matchWidth().apply { bottomMargin = dp(8) })
        return g
    }

    private fun addRow(target: LinearLayout, row: View) {
        if (target.childCount > 0) {
            target.addView(
                View(this).apply { setBackgroundColor((themeColor(R.attr.themeTextMuted) and 0x00FFFFFF) or 0x33000000) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) }
            )
        }
        target.addView(row, matchWidth())
    }

    private fun label(
        value: String,
        size: Float,
        bold: Boolean = false,
        muted: Boolean = false,
        secondary: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(
            themeColor(
                when {
                    muted -> R.attr.themeTextMuted
                    secondary -> R.attr.themeTextSecondary
                    else -> R.attr.themeTextPrimary
                }
            )
        )
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int, pad: IntArray) {
        root.addView(TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(pad[0]), dp(pad[1]), dp(pad[2]), dp(pad[3]))
        }, matchWidth())
    }

    private fun header(value: String) {
        root.addView(TextView(this).apply {
            text = value
            textSize = 12f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(dp(8), dp(18), dp(8), dp(6))
        }, matchWidth())
    }

    private fun iosSwitch(initial: Boolean) = SwitchCompat(this).apply {
        isChecked = initial
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(52, 199, 89), Color.argb(102, 142, 142, 147))
        )
    }

    private fun switchRow(
        target: LinearLayout,
        title: String,
        description: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 16f))
        labels.addView(label(description, 12f, muted = true))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
        row.addView(iosSwitch(initial).apply { setOnCheckedChangeListener { _, checked -> onChange(checked) } })
        addRow(target, row)
    }

    private fun statusRow(target: LinearLayout, title: String, ok: Boolean, hint: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!ok) onClick() }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 16f))
        labels.addView(label(if (ok) "Ready" else hint, 12f, muted = true))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = if (ok) "\u2713" else "Fix"
            textSize = if (ok) 20f else 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (ok) Color.rgb(52, 199, 89) else Color.rgb(255, 159, 10))
        })
        addRow(target, row)
    }

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = rounded((themeColor(R.attr.themeTextMuted) and 0x00FFFFFF) or 0x26000000, 10)
        }
        options.forEachIndexed { index, name ->
            val isSelected = index == selected
            bar.addView(TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.BLACK else themeColor(R.attr.themeTextPrimary))
                background = if (isSelected) rounded(Color.WHITE, 8) else null
                isClickable = true
                setOnClickListener { onSelect(index) }
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        root.addView(bar, matchWidth().apply { bottomMargin = dp(4) })
    }

    private fun button(textValue: String, onClick: () -> Unit) = TextView(this).apply {
        text = textValue
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(10, 132, 255), 12)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = matchWidth().apply { topMargin = dp(8) }
    }

    override fun onPause() {
        savePrompt()
        super.onPause()
    }
}
