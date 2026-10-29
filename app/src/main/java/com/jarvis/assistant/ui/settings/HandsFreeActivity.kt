package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.HopeAssistantState
import com.jarvis.assistant.databinding.ActivityHandsFreeBinding
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HandsFreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandsFreeBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private var pendingWakePermissionGrant = false

    private val wakePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = PermissionHelper.hasRecordAudioPermission(this) &&
            PermissionHelper.hasNotificationPermission(this)
        if (pendingWakePermissionGrant && granted) {
            preferences.isWakeWordEnabled = true
            binding.switchHeyHopeStandby.isChecked = true
            Toast.makeText(
                this,
                "Hey HOPE is enabled. Tap Enter standby when you are ready to start on-device listening.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            preferences.isWakeWordEnabled = false
            binding.switchHeyHopeStandby.isChecked = false
            Toast.makeText(
                this,
                "Microphone and notification permissions are required for standby listening.",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingWakePermissionGrant = false
        updateVoiceControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHandsFreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
        observeVoiceAssistantState()
    }

    override fun onResume() {
        super.onResume()
        binding.switchHeyHopeStandby.isChecked = preferences.isWakeWordEnabled
        binding.switchVoiceClipboard.isChecked = preferences.isVoiceClipboardEnabled
        updateServiceStatus()
        updateVoiceControls()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAllPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        binding.switchHeyHopeStandby.isChecked = preferences.isWakeWordEnabled
        binding.switchHeyHopeStandby.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                preferences.isWakeWordEnabled = false
                if (JarvisConversationService.isStandby.value ||
                    (!JarvisConversationService.isSessionOn.value &&
                        JarvisConversationService.voiceAssistantState.value == HopeAssistantState.ERROR)
                ) {
                    startConversationService(JarvisConversationService.ACTION_STOP_ALL)
                }
                updateVoiceControls()
                return@setOnCheckedChangeListener
            }
            enableWakeWordAfterPermissionCheck()
        }

        val wakeProgress = (preferences.wakeWordConfidenceThreshold * 100f)
            .roundToInt().minus(50).coerceIn(0, 45)
        binding.seekWakeConfidence.progress = wakeProgress
        binding.tvWakeConfidence.text = "Wake confidence: ${wakeProgress + 50}%"
        binding.seekWakeConfidence.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = progress.coerceIn(0, 45) + 50
                    binding.tvWakeConfidence.text = "Wake confidence: $percent%"
                    if (fromUser) preferences.wakeWordConfidenceThreshold = percent / 100f
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            }
        )

        binding.switchVoiceClipboard.isChecked = preferences.isVoiceClipboardEnabled
        binding.switchVoiceClipboard.setOnCheckedChangeListener { _, checked ->
            preferences.isVoiceClipboardEnabled = checked
        }

        binding.btnWakeStandbyAction.setOnClickListener {
            when {
                JarvisConversationService.isStandby.value -> {
                    startConversationService(JarvisConversationService.ACTION_START_CONVERSATION)
                }
                JarvisConversationService.isSessionOn.value &&
                    !preferences.isWakeWordEnabled -> {
                    startConversationService(JarvisConversationService.ACTION_STOP_ALL)
                }
                !preferences.isWakeWordEnabled -> {
                    Toast.makeText(this, "Enable Hey HOPE standby listening first.", Toast.LENGTH_SHORT).show()
                }
                else -> enableWakeWordAfterPermissionCheck(startImmediately = true)
            }
        }

        binding.btnConfigureApi.setOnClickListener {
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        binding.btnHandsFreeOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            openUrl("https://t.me/+DmyO7NFUia01YTVl")
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            openUrl("https://github.com/yashpawar9274/HOPE-V3")
        }

        binding.tvFooterHandsFree.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }
    }

    private fun updateServiceStatus() {
        val isEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (isEnabled) {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.tvAccessibilityStatus.text = "HANDS-FREE SERVICE: ACTIVE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.btnToggleAccessibility.text = "Accessibility Service is Active"
        } else {
            if (preferences.isVoiceClipboardEnabled) {
                preferences.isVoiceClipboardEnabled = false
                binding.switchVoiceClipboard.isChecked = false
            }
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.tvAccessibilityStatus.text = "SERVICE DISABLED • TAP TO ENABLE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.btnToggleAccessibility.text = "Enable Accessibility Service"
        }
    }

    private fun enableWakeWordAfterPermissionCheck(startImmediately: Boolean = false) {
        val missing = buildList {
            if (!PermissionHelper.hasRecordAudioPermission(this@HandsFreeActivity)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !PermissionHelper.hasNotificationPermission(this@HandsFreeActivity)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            pendingWakePermissionGrant = true
            wakePermissionsLauncher.launch(missing.toTypedArray())
            return
        }
        preferences.isWakeWordEnabled = true
        binding.switchHeyHopeStandby.isChecked = true
        if (startImmediately) {
            startConversationService(JarvisConversationService.ACTION_ENTER_STANDBY)
        }
        updateVoiceControls()
    }

    private fun observeVoiceAssistantState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JarvisConversationService.voiceAssistantState.collect {
                    updateVoiceControls(it)
                }
            }
        }
    }

    private fun updateVoiceControls(state: HopeAssistantState = JarvisConversationService.voiceAssistantState.value) {
        val stateText = when (state) {
            HopeAssistantState.ACTIVE_LISTENING ->
                "Microphone on. Voice commands use your configured HOPE conversation service."
            HopeAssistantState.PROCESSING -> "HOPE is processing the active conversation."
            HopeAssistantState.SPEAKING -> "HOPE is speaking. Wake detection is paused."
            HopeAssistantState.STANDBY ->
                "Standby: only local Hey HOPE detection is active; ambient audio is not saved or uploaded."
            HopeAssistantState.WAKE_WORD_DETECTED -> "Hey HOPE detected. Starting your voice session."
            HopeAssistantState.ERROR ->
                JarvisConversationService.liveError.value?.message ?: "Listening stopped. Open HOPE to retry."
            HopeAssistantState.OFF -> "Microphone listening is off."
        }
        binding.tvVoiceAssistantMode.text = stateText
        binding.btnWakeStandbyAction.text = when {
            JarvisConversationService.isStandby.value -> "Wake HOPE now"
            JarvisConversationService.isSessionOn.value && preferences.isWakeWordEnabled -> "Enter standby"
            JarvisConversationService.isSessionOn.value -> "Stop microphone"
            preferences.isWakeWordEnabled -> "Start standby"
            else -> "Enable standby in Settings"
        }
        binding.btnWakeStandbyAction.isEnabled =
            JarvisConversationService.isStandby.value ||
                JarvisConversationService.isSessionOn.value ||
                preferences.isWakeWordEnabled
    }

    private fun startConversationService(action: String) {
        val intent = Intent(this, JarvisConversationService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (error: Exception) {
            android.util.Log.e("HandsFreeActivity", "Could not start HOPE's foreground voice service.", error)
            Toast.makeText(
                this,
                "Android could not start microphone listening. Open HOPE while visible and try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
