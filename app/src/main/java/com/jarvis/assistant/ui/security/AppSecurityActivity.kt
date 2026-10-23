package com.jarvis.assistant.ui.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.security.AppCredentialPolicy
import com.jarvis.assistant.security.AppCredentialType
import com.jarvis.assistant.security.AppLockConfig
import com.jarvis.assistant.security.AppLockManager
import com.jarvis.assistant.security.CredentialCheck
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

class AppSecurityActivity : AppCompatActivity() {

    private enum class Screen { UNLOCK, CONFIGURE, VERIFY_TO_MANAGE, SETUP }

    private lateinit var root: LinearLayout
    private var input: EditText? = null
    private var confirmation: EditText? = null
    private var typeSpinner: Spinner? = null
    private var timeoutSpinner: Spinner? = null
    private var patternInput: PatternInputView? = null
    private var patternConfirmation: PatternInputView? = null
    private lateinit var messageView: TextView
    private var screen = Screen.CONFIGURE
    private var setupType = AppCredentialType.PIN
    private var configAtLaunch = AppLockConfig()
    private var isChangingCredential = false

    private val deviceRecoveryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                AppLockManager.recoverAfterSystemAuthentication()
                Toast.makeText(this, "HOPE app lock reset. Set a new app credential in Settings.", Toast.LENGTH_LONG).show()
                finish()
            } catch (error: Exception) {
                showMessage(error.message ?: "The app lock could not be recovered.")
            }
        } else {
            showMessage("Android authentication was cancelled. The HOPE app lock remains enabled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ThemeManager.applyDynamicBackground(window.decorView)
        AppLockManager.updateLockActivityShowing(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.UNLOCK || AppLockManager.isLocked) {
                    Toast.makeText(
                        this@AppSecurityActivity,
                        "Unlock HOPE to access its private screens.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    finish()
                }
            }
        })
        configAtLaunch = AppLockManager.config
        screen = when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_UNLOCK -> Screen.UNLOCK
            MODE_CHANGE -> {
                isChangingCredential = true
                Screen.VERIFY_TO_MANAGE
            }
            else -> if (configAtLaunch.enabled) Screen.VERIFY_TO_MANAGE else Screen.CONFIGURE
        }
        render()
    }

    override fun onDestroy() {
        AppLockManager.updateCredentialEntryVisible(false)
        AppLockManager.updateLockActivityShowing(false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (AppLockManager.isLocked && screen != Screen.UNLOCK) {
            startActivity(
                Intent(this, AppSecurityActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_UNLOCK)
            )
            finish()
        }
    }

    private fun render() {
        AppLockManager.updateCredentialEntryVisible(screen != Screen.CONFIGURE)
        val padding = dp(24)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        input = null
        confirmation = null
        patternInput = null
        patternConfirmation = null
        typeSpinner = null
        timeoutSpinner = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)
        addText("HOPE SECURITY", 12f, true, R.attr.themeAccent, 16)
        addText(
            when (screen) {
                Screen.UNLOCK -> "Unlock HOPE"
                Screen.CONFIGURE -> "Protect your HOPE app"
                Screen.VERIFY_TO_MANAGE -> "Verify your app credential"
                Screen.SETUP -> if (isChangingCredential) "Change app credential" else "Create app credential"
            },
            24f,
            true,
            R.attr.themeTextPrimary,
            10
        )
        addText(
            "This credential protects HOPE only. Android device credentials are never requested or stored by HOPE.",
            14f,
            false,
            R.attr.themeTextMuted,
            22
        )
        messageView = addText("", 13f, false, R.attr.themeAccent, 10).apply {
            visibility = View.GONE
        }

        when (screen) {
            Screen.UNLOCK -> renderUnlock()
            Screen.CONFIGURE -> renderSetup()
            Screen.VERIFY_TO_MANAGE -> renderManagementVerification()
            Screen.SETUP -> renderCredentialCreation()
        }
    }

    private fun renderUnlock() {
        if (AppLockManager.storageError != null) {
            showMessage("The encrypted app-lock data is unavailable. Recover with Android device authentication to reset the HOPE app lock.")
        }
        addCredentialEntry(configAtLaunch.type, "HOPE app credential")
        if (configAtLaunch.type == AppCredentialType.PATTERN) {
            addButton("Clear pattern") { patternInput?.clearPattern() }
        }
        addButton("Unlock HOPE") { verifyCredential { finish() } }
        if (configAtLaunch.biometricEnabled && AppLockManager.storageError == null &&
            AppLockManager.lockoutRemainingMillis == 0L
        ) {
            addButton("Use fingerprint or face unlock") { showBiometricPrompt() }
        }
        addButton("Forgot credential? Recover with Android authentication") {
            beginDeviceRecovery()
        }
        addText(
            "Repeated incorrect attempts trigger a timed lockout. Recovery verifies you with Android's system prompt and resets only HOPE's app lock.",
            12f,
            false,
            R.attr.themeTextMuted,
            0
        )
    }

    private fun renderSetup() {
        if (AppLockManager.storageError != null) {
            showMessage("Protected security storage is unavailable. Use Android-authenticated recovery from the lock screen before setting a new app credential.")
            addButton("Close") { finish() }
            addButton("Recover with Android authentication") { beginDeviceRecovery() }
            return
        }
        if (configAtLaunch.enabled) {
            addText("HOPE app lock is enabled.", 16f, true, R.attr.themeTextPrimary, 12)
            renderTimeoutSelector(configAtLaunch)
            addButton("Change app credential") { startActivity(changeIntent()) }
            addButton("Disable app lock") { startActivity(Intent(this, AppSecurityActivity::class.java).putExtra(EXTRA_MODE, MODE_DISABLE)) }
            if (configAtLaunch.biometricEnabled) {
                addButton("Turn off biometric unlock") {
                    try {
                        AppLockManager.updateBiometricEnabled(false)
                        configAtLaunch = AppLockManager.config
                        render()
                        showMessage("Biometric unlock is off.")
                    } catch (error: Exception) {
                        showMessage(error.message ?: "Could not turn off biometric unlock.")
                    }
                }
            } else {
                addButton("Set up biometric unlock") {
                    showBiometricPrompt(enableForApp = true)
                }
            }
            addButton("Done") { finish() }
        } else {
            addText(
                "Set a PIN, a 3×3 pattern, or an alphanumeric password. Credentials stay on this device.",
                14f,
                false,
                R.attr.themeTextMuted,
                12
            )
            typeSpinner = addTypeSpinner()
            renderTimeoutSelector(AppLockConfig())
            addButton("Set up app lock") {
                setupType = selectedType()
                isChangingCredential = false
                screen = Screen.SETUP
                render()
            }
        }
    }

    private fun renderManagementVerification() {
        addText("Enter your current HOPE app credential to manage security settings.", 14f, false, R.attr.themeTextMuted, 12)
        addCredentialEntry(configAtLaunch.type, "Current HOPE app credential")
        addButton("Verify") {
            verifyCredential {
                if (isChangingCredential) {
                    screen = Screen.SETUP
                    render()
                } else if (intent.getStringExtra(EXTRA_MODE) == MODE_DISABLE) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Disable HOPE app lock?")
                        .setMessage("HOPE's app-only lock will be turned off. Android's device lock will not change.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Disable") { _, _ ->
                            lifecycleScope.launch {
                                try {
                                    AppLockManager.disable()
                                    finish()
                                } catch (error: Exception) {
                                    showMessage(error.message ?: "Could not disable the app lock.")
                                }
                            }
                        }
                        .show()
                } else {
                    screen = Screen.CONFIGURE
                    render()
                }
            }
        }
        addButton("Recover with Android authentication") { beginDeviceRecovery() }
        if (intent.getStringExtra(EXTRA_MODE) == MODE_DISABLE) {
            addButton("Cancel") { finish() }
        }
    }

    private fun renderCredentialCreation() {
        addText("Credential type: ${setupType.label}", 15f, true, R.attr.themeTextPrimary, 12)
        addCredentialEntry(setupType, "New ${setupType.label.lowercase()}")
        addCredentialEntry(setupType, "Confirm ${setupType.label.lowercase()}", confirmationField = true)
        if (setupType == AppCredentialType.PATTERN) {
            addButton("Clear both patterns") {
                patternInput?.clearPattern()
                patternConfirmation?.clearPattern()
            }
        }
        renderTimeoutSelector(configAtLaunch)
        addButton(if (isChangingCredential) "Save new credential" else "Enable app lock") {
            saveNewCredential()
        }
        addButton("Cancel") {
            if (configAtLaunch.enabled) {
                screen = Screen.CONFIGURE
                render()
            } else finish()
        }
    }

    private fun addCredentialEntry(
        type: AppCredentialType,
        label: String,
        confirmationField: Boolean = false
    ) {
        val entry = when (type) {
            AppCredentialType.PATTERN -> PatternInputView(this).also {
                it.contentDescription = label
                it.minimumHeight = dp(250)
                it.setBackgroundResource(R.drawable.bg_jarvis_card)
                it.setPadding(dp(12), dp(12), dp(12), dp(12))
                if (Build.VERSION.SDK_INT >= 34) {
                    it.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
                }
                if (confirmationField) patternConfirmation = it else patternInput = it
            }
            else -> EditText(this).also {
                it.hint = label
                it.setSingleLine(true)
                it.inputType = if (type == AppCredentialType.PIN) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                it.contentDescription = label
                if (Build.VERSION.SDK_INT >= 34) {
                    it.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
                }
                if (confirmationField) confirmation = it else input = it
            }
        }
        root.addView(entry, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (type == AppCredentialType.PATTERN) dp(270) else dp(58)
        ).apply { bottomMargin = dp(12) })
    }

    private fun addTypeSpinner(): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AppCredentialType.entries.map { it.label }
        )
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(12) })
        return spinner
    }

    private fun renderTimeoutSelector(config: AppLockConfig) {
        addText("Lock when HOPE is in the background", 14f, true, R.attr.themeTextPrimary, 4)
        val spinner = Spinner(this)
        timeoutSpinner = spinner
        val options = listOf("Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(listOf(0, 1, 5, 15).indexOf(config.lockTimeoutMinutes).coerceAtLeast(0))
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(12) })
        if (screen == Screen.CONFIGURE && config.enabled) {
            addButton("Save timeout") {
                try {
                    AppLockManager.updateTimeout(selectedTimeout())
                    configAtLaunch = AppLockManager.config
                    showMessage("App-lock timeout saved.")
                } catch (error: Exception) {
                    showMessage(error.message ?: "Could not save the timeout.")
                }
            }
        }
    }

    private fun selectedTimeout(): Int = listOf(0, 1, 5, 15)
        .getOrElse(timeoutSpinner?.selectedItemPosition ?: 0) { 0 }

    private fun selectedType(): AppCredentialType =
        AppCredentialType.entries.getOrElse(typeSpinner?.selectedItemPosition ?: 0) { AppCredentialType.PIN }

    private fun verifyCredential(onAccepted: () -> Unit) {
        val credential = readCredential(configAtLaunch.type, input, patternInput)
        lifecycleScope.launch {
            try {
                when (val result = AppLockManager.verify(credential)) {
                    CredentialCheck.Accepted -> {
                        configAtLaunch = AppLockManager.config
                        onAccepted()
                    }
                    CredentialCheck.Rejected -> showMessage("That HOPE app credential is incorrect.")
                    is CredentialCheck.LockedOut ->
                        showMessage("Too many attempts. Try again in ${((result.remainingMillis + 999) / 1000)} seconds.")
                }
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not verify the HOPE app credential.")
            } finally {
                credential.fill('\u0000')
                input?.text?.clear()
                patternInput?.clearPattern()
            }
        }
    }

    private fun saveNewCredential() {
        val first = readCredential(setupType, input, patternInput)
        val second = readCredential(setupType, confirmation, patternConfirmation)
        val validation = AppCredentialPolicy.validate(setupType, first)
        if (validation != null) {
            first.fill('\u0000')
            second.fill('\u0000')
            showMessage(validation)
            return
        }
        if (!first.contentEquals(second)) {
            first.fill('\u0000')
            second.fill('\u0000')
            showMessage("The credentials do not match. Enter them again.")
            input?.text?.clear()
            confirmation?.text?.clear()
            patternInput?.clearPattern()
            patternConfirmation?.clearPattern()
            return
        }
        val timeout = selectedTimeout()
        lifecycleScope.launch {
            try {
                AppLockManager.setCredential(setupType, first, timeout)
                Toast.makeText(this@AppSecurityActivity, "HOPE app lock enabled.", Toast.LENGTH_LONG).show()
                finish()
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save the app lock.")
            } finally {
                first.fill('\u0000')
                second.fill('\u0000')
                input?.text?.clear()
                confirmation?.text?.clear()
                patternInput?.clearPattern()
                patternConfirmation?.clearPattern()
            }
        }
    }

    private fun readCredential(
        type: AppCredentialType,
        textInput: EditText?,
        patternView: PatternInputView?
    ): CharArray = when (type) {
        AppCredentialType.PATTERN -> patternView?.pattern().orEmpty().toCharArray()
        else -> textInput?.text?.let { editable ->
            CharArray(editable.length) { index -> editable[index] }
        } ?: CharArray(0)
    }

    private fun showBiometricPrompt(enableForApp: Boolean = false) {
        if (!enableForApp && AppLockManager.lockoutRemainingMillis > 0L) {
            showMessage(
                "Wait ${((AppLockManager.lockoutRemainingMillis + 999) / 1000)} seconds or recover with Android authentication."
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            showMessage("A strong biometric is not available or enrolled on this device.")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    when {
                        enableForApp -> {
                            try {
                                AppLockManager.updateBiometricEnabled(true)
                                configAtLaunch = AppLockManager.config
                                render()
                                showMessage("Biometric app unlock enabled.")
                            } catch (error: Exception) {
                                showMessage(error.message ?: "Could not enable biometric unlock.")
                            }
                        }
                        else -> {
                            try {
                                AppLockManager.unlockWithBiometric()
                                finish()
                            } catch (error: Exception) {
                                showMessage(error.message ?: "Could not unlock HOPE.")
                            }
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showMessage("Biometric authentication was not completed.")
                }

                override fun onAuthenticationFailed() {
                    showMessage("Biometric not recognized. Try again or use your HOPE app credential.")
                }
            }
        )
        val title = if (enableForApp) "Enable biometric unlock for HOPE" else "Unlock HOPE"
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Android verifies your biometric. This does not change your phone lock.")
                .setNegativeButtonText("Use HOPE app credential")
                .build()
        )
    }

    private fun beginDeviceRecovery() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset HOPE app lock?")
            .setMessage("Android will ask you to authenticate using the phone's own system lock. HOPE will not see or store that credential. Successful system authentication disables only the HOPE app lock so you can set a new one.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!keyguard.isDeviceSecure) {
                    showMessage("Set a screen lock in Android Settings before using recovery.")
                    return@setPositiveButton
                }
                @Suppress("DEPRECATION")
                val confirmIntent = keyguard.createConfirmDeviceCredentialIntent(
                    "Recover HOPE app lock",
                    "Authenticate with Android to reset HOPE's app-only lock."
                )
                if (confirmIntent == null) {
                    showMessage("Android did not provide a device-authentication screen. The app lock remains enabled.")
                } else {
                    deviceRecoveryLauncher.launch(confirmIntent)
                }
            }
            .show()
    }

    private fun addButton(label: String, action: () -> Unit) {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun addText(
        text: String,
        size: Float,
        bold: Boolean,
        colorAttribute: Int,
        bottomMargin: Int
    ): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            val value = android.util.TypedValue()
            theme.resolveAttribute(colorAttribute, value, true)
            setTextColor(value.data)
        }
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.bottomMargin = dp(bottomMargin) })
        return view
    }

    private fun showMessage(message: String) {
        if (!::messageView.isInitialized) return
        messageView.text = message
        messageView.visibility = View.VISIBLE
    }

    private fun changeIntent() =
        Intent(this, AppSecurityActivity::class.java).putExtra(EXTRA_MODE, MODE_CHANGE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "app_security_mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_CHANGE = "change"
        const val MODE_DISABLE = "disable"
    }
}

private class PatternInputView(context: Context) : View(context) {
    private val selected = mutableListOf<Int>()
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.resources.displayMetrics.density * 4f
    }
    private val points = Array(9) { 0f to 0f }
    private val density = context.resources.displayMetrics.density

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Draw your 3 by 3 unlock pattern"
    }

    fun pattern(): String = selected.joinToString("")
    fun clearPattern() {
        selected.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val inset = side * 0.14f
        val left = (width - side) / 2f + inset
        val top = (height - side) / 2f + inset
        val spacing = (side - inset * 2f) / 2f
        for (row in 0..2) for (column in 0..2) {
            val index = row * 3 + column
            points[index] = (left + column * spacing) to (top + row * spacing)
        }
        val accent = resolveThemeColor(R.attr.themeAccent)
        val muted = resolveThemeColor(R.attr.themeTextMuted)
        dots.color = muted
        lines.color = accent
        val path = Path()
        selected.forEachIndexed { index, dot ->
            val (x, y) = points[dot]
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, lines)
        points.forEachIndexed { index, (x, y) ->
            dots.color = if (index in selected) accent else muted
            canvas.drawCircle(x, y, if (index in selected) 11f * density else 8f * density, dots)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val radius = 24f * density
                val hit = points.indices.firstOrNull { index ->
                    val (x, y) = points[index]
                    kotlin.math.hypot((event.x - x).toDouble(), (event.y - y).toDouble()) <= radius &&
                        index !in selected
                }
                if (hit != null) {
                    selected.add(hit)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                clearPattern()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }
}
