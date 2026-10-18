package com.jarvis.assistant.telephony

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

class PhoneSetupActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PhoneRoleHelper.isDefaultPhoneApp(this)) {
            status.text = "HOPE is now your default phone app."
            openDialer()
        } else {
            status.text = "Setup is not complete. Android kept your current phone app as default. You can continue using HOPE and retry later."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (PhoneRoleHelper.isDefaultPhoneApp(this)) {
            openDialer()
            return
        }
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && PhoneRoleHelper.isDefaultPhoneApp(this)) {
            status.text = "HOPE is now your default phone app."
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }
        ThemeManager.applyDynamicBackground(root)
        root.addView(TextView(this).apply {
            text = "HOPE PHONE SETUP"
            textSize = 13f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@PhoneSetupActivity, R.color.accent_sapphire))
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Your calls, inside HOPE"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(14))
            setTextColor(themeTextColor())
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "HOPE includes its own dial pad, contacts, call history, and incoming/active call screens. Android requires your approval before HOPE can become the default phone app. No separate dialer app is downloaded or installed."
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(themeTextColor())
        }, matchWidth())
        status = TextView(this).apply {
            text = "Choose whether to make HOPE your default phone app."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(18))
            setTextColor(themeTextColor())
        }
        root.addView(status, matchWidth())
        root.addView(Button(this).apply {
            text = "Set HOPE as Default Phone App"
            isAllCaps = false
            setOnClickListener { requestDialerRole() }
        }, matchWidth().apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = "Open HOPE Phone"
            isAllCaps = false
            setOnClickListener {
                if (PhoneRoleHelper.isDefaultPhoneApp(this@PhoneSetupActivity)) openDialer()
                else status.text = "Set HOPE as default in Android's role prompt first. HOPE AI remains available without this role."
            }
        }, matchWidth().apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = "Continue to HOPE AI"
            isAllCaps = false
            setOnClickListener { finish() }
        }, matchWidth().apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun requestDialerRole() {
        val requestIntent = PhoneRoleHelper.requestDefaultPhoneApp(this)
        if (requestIntent == null) {
            status.text = "This device does not expose Android's default phone app role. HOPE AI remains available, but the system phone app will handle calls."
            return
        }
        try {
            roleRequest.launch(requestIntent)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not launch Android's default phone app consent prompt.", error)
            status.text = "Android could not open the phone-app setup prompt. Try again or choose your phone app in Android settings."
            Toast.makeText(this, "Could not open Android's dialer role prompt.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDialer() {
        startActivity(Intent(this, HopeDialerActivity::class.java))
        finish()
    }

    private fun themeTextColor(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "PhoneSetupActivity"
    }
}
