package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.security.AppSecurityActivity
import com.jarvis.assistant.ui.voice.SpeakerProfilesActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.AssistantRoleHelper
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val assistantRoleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (_binding == null) return@registerForActivityResult
        updateDefaultAssistantStatus()
        if (AssistantRoleHelper.isDefaultAssistant(requireContext())) {
            Toast.makeText(requireContext(), "HOPE is your default assistant. Hold the system assistant gesture to start a voice session.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), "Android did not set HOPE as the assistant. Choose HOPE in the system prompt and approve the role.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.jarvis.assistant.vision.ScreenRecordingService.state.collect { state ->
                    binding.tvScreenRecordingStatus.text = when (state) {
                        com.jarvis.assistant.vision.ScreenRecordingState.IDLE ->
                            com.jarvis.assistant.vision.ScreenRecordingService.lastSavedName.value
                                .takeIf { it.isNotBlank() }
                                ?.let { "Saved locally: ${it.substringAfterLast('/')}" }
                                ?: "Screen + supported app audio"
                        com.jarvis.assistant.vision.ScreenRecordingState.STARTING -> "Preparing recording…"
                        com.jarvis.assistant.vision.ScreenRecordingState.RECORDING -> "Recording — tap to stop"
                        com.jarvis.assistant.vision.ScreenRecordingState.STOPPING -> "Saving recording…"
                        com.jarvis.assistant.vision.ScreenRecordingState.ERROR ->
                            com.jarvis.assistant.vision.ScreenRecordingService.lastError.value
                                .takeIf { it.isNotBlank() }
                                ?.let { "Failed: $it" }
                                ?: "Recording failed — tap to retry"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "YASH" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "HOPE OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language

        // Persona & Voice
        val currentPersona = preferences.personality
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode
        binding.switchCriticalThinking.isChecked = preferences.isCriticalThinkingEnabled
        updateAutoReplyStatus()
        updateDefaultAssistantStatus()

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"
        val localModelStatus = if (JarvisApp.instance.gemmaModelManager.modelFile.isFile) {
            "Gemma downloaded"
        } else {
            "Gemma not downloaded"
        }
        binding.tvAiRoutingSummary.text = "${preferences.aiMode.name.lowercase().replaceFirstChar { it.uppercase() }} • $localModelStatus"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }
    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowSpeakerProfiles.setOnClickListener {
            startActivity(Intent(requireContext(), SpeakerProfilesActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchCriticalThinking.setOnCheckedChangeListener { _, isChecked ->
            preferences.isCriticalThinkingEnabled = isChecked
            val msg = if (isChecked) {
                "Critical Thinking enabled for new voice sessions and chat replies"
            } else {
                "Critical Thinking disabled"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
        binding.rowCriticalThinking.setOnClickListener {
            binding.switchCriticalThinking.isChecked = !binding.switchCriticalThinking.isChecked
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowLocalAi.setOnClickListener {
            startActivity(Intent(requireContext(), GemmaModelActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowScreenRecording.setOnClickListener {
            (activity as? com.jarvis.assistant.ui.home.MainActivity)?.toggleScreenRecording()
        }

        binding.rowLocationAwareness.setOnClickListener {
            startActivity(Intent(requireContext(), HopeLocationActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        binding.rowAutoReply.setOnClickListener {
            startActivity(Intent(requireContext(), AutoReplySettingsActivity::class.java))
        }

        binding.rowIncomingCall.setOnClickListener {
            startActivity(Intent(requireContext(), IncomingCallSettingsActivity::class.java))
        }

        binding.rowDefaultAssistant.setOnClickListener {
            try {
                assistantRoleRequest.launch(
                    AssistantRoleHelper.requestOrOpenSettings(requireContext())
                )
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Could not open Android assistant role selection.", e)
                Toast.makeText(
                    requireContext(),
                    "Could not open Android assistant selection: ${e.message ?: "system settings are unavailable"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppLockSecurity.setOnClickListener {
            startActivity(Intent(requireContext(), AppSecurityActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("HOPE App Identity")
                .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }

        // Social Media Community Buttons
        binding.btnSocialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnSocialInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/theitsash")
        }

        binding.btnSocialYoutube.setOnClickListener {
            openUrl("https://youtube.com/@theitsash")
        }

        binding.tvFooterCredit.setOnClickListener {
            openUrl("https://wa.me/+917385066631")
        }
    }

    private fun updateAutoReplyStatus() {
        val enabled = com.jarvis.assistant.util.PermissionHelper.isNotificationListenerEnabled(
            requireContext()
        )
        binding.tvAutoReplyStatus.text = if (enabled) {
            "Real-time notification access: ON"
        } else {
            "Real-time notification access: OFF • Tap to enable"
        }
        binding.tvAutoReplyStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.status_green else R.color.accent_sapphire
            )
        )
    }

    private fun updateDefaultAssistantStatus() {
        val isDefault = AssistantRoleHelper.isDefaultAssistant(requireContext())
        binding.tvDefaultAssistantStatus.text = if (isDefault) {
            "HOPE is default • hold the system assistant gesture to start voice"
        } else {
            "Not set • tap here, then choose HOPE in Android"
        }
        binding.tvDefaultAssistantStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isDefault) R.color.status_green else R.color.accent_sapphire
            )
        )
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words & Sensitivity")
            .setMessage("• Wake Words: 'HOPE', 'Hey HOPE'\n• Acoustic Trigger: Double Clap detection\n• Standby Mode: Low-power local Vosk offline engine\n\nWhen standby listening is active, HOPE awakens instantly upon hearing your voice.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("HOPE is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "YASH" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name HOPE should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
