package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.media.MediaDownloadActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.security.AppSecurityActivity
import com.jarvis.assistant.ui.voice.SpeakerProfilesActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.AssistantRoleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }
    private var selectedSettingsFilter = SettingsFilter.ALL
    private var settingsSections: List<SettingsSection> = emptyList()

    private enum class SettingsFilter(val label: String) {
        ALL("All"),
        AI_VOICE("AI & Voice"),
        DEVICE("Device"),
        AUTOMATION("Automation"),
        SECURITY("Security")
    }

    private data class SettingEntry(
        val view: View,
        val categories: Set<SettingsFilter>,
        val searchAliases: String = ""
    )

    private data class SettingsSection(
        val heading: TextView,
        val container: LinearLayout,
        val entries: List<SettingEntry>
    )

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
        setupSettingsBrowser()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
            renderSettingsFilters()
            applySettingsFilter()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
            renderSettingsFilters()
            applySettingsFilter()
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
        updateMeetingModeStatus()
        updateDefaultAssistantStatus()

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Online (internet required)"
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

        binding.rowSmartClipboardSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SmartClipboardSettingsActivity::class.java))
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

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowWordSearch.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.wordsearch.WordSearchActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowTimeManager.setOnClickListener {
            startActivity(Intent(requireContext(), TimeManagerActivity::class.java))
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

        binding.rowMediaDownload.setOnClickListener {
            startActivity(Intent(requireContext(), MediaDownloadActivity::class.java))
        }

        binding.rowMeetingMode.setOnClickListener {
            startActivity(Intent(requireContext(), MeetingModeActivity::class.java))
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

    private fun setupSettingsBrowser() {
        val aiVoice = setOf(SettingsFilter.AI_VOICE)
        val device = setOf(SettingsFilter.DEVICE)
        val automation = setOf(SettingsFilter.AUTOMATION)
        val security = setOf(SettingsFilter.SECURITY)
        val deviceAndSecurity = setOf(SettingsFilter.DEVICE, SettingsFilter.SECURITY)
        val deviceAndAutomation = setOf(SettingsFilter.DEVICE, SettingsFilter.AUTOMATION)

        settingsSections = listOf(
            SettingsSection(
                binding.tvSectionVoice,
                binding.containerVoiceSettings,
                listOf(
                    SettingEntry(binding.rowOwnerVoice, aiVoice, "owner voice biometrics enrollment"),
                    SettingEntry(binding.rowSpeakerProfiles, aiVoice, "speaker recognition profiles"),
                    SettingEntry(binding.rowLanguage, aiVoice, "conversation language hindi english"),
                    SettingEntry(binding.rowPersonaVoice, aiVoice, "persona hope friday edith ultron voice"),
                    SettingEntry(binding.rowSilentMode, aiVoice, "silent execution"),
                    SettingEntry(binding.rowCriticalThinking, aiVoice, "critical thinking honesty")
                )
            ),
            SettingsSection(
                binding.tvSectionAiFeatures,
                binding.containerAiFeatureSettings,
                listOf(SettingEntry(binding.rowSmartClipboardSettings, aiVoice, "clipboard copy paste privacy"))
            ),
            SettingsSection(
                binding.tvSectionSystem,
                binding.containerSystemSettings,
                listOf(
                    SettingEntry(binding.rowApiConnection, aiVoice, "gemini api model key connection"),
                    SettingEntry(binding.rowMemory, aiVoice, "neural memory cloud recall"),
                    SettingEntry(binding.rowWordSearch, device, "word search puzzle game"),
                    SettingEntry(binding.rowPermissions, deviceAndSecurity, "android permissions access authorization"),
                    SettingEntry(binding.rowTimeManager, deviceAndAutomation, "calendar alarm timer reminders"),
                    SettingEntry(binding.rowLocationAwareness, device, "location privacy"),
                    SettingEntry(binding.rowOverlayAvatars, device, "floating avatar overlay"),
                    SettingEntry(binding.rowApplications, automation, "app control hands free whatsapp youtube"),
                    SettingEntry(binding.rowAutoReply, automation, "smart auto reply notification"),
                    SettingEntry(binding.rowMediaDownload, automation, "authorized media downloader"),
                    SettingEntry(binding.rowMeetingMode, automation, "decline calls meeting"),
                    SettingEntry(binding.rowIncomingCall, automation, "incoming call announcement"),
                    SettingEntry(binding.rowDefaultAssistant, automation, "android default assistant")
                )
            ),
            SettingsSection(
                binding.tvSectionSecurity,
                binding.containerSecuritySettings,
                listOf(
                    SettingEntry(binding.rowAppTheme, security, "appearance colors orb theme"),
                    SettingEntry(binding.rowAppLockSecurity, security, "app lock pin password biometrics"),
                    SettingEntry(binding.rowPrivacyPolicy, security, "privacy data shield"),
                    SettingEntry(binding.rowAppIdentity, security, "app identity integrity publisher certificate")
                )
            )
        )

        binding.etSettingsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                applySettingsFilter()
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        renderSettingsFilters()
        applySettingsFilter()
    }

    private fun renderSettingsFilters() {
        if (_binding == null || settingsSections.isEmpty()) return
        val filters = binding.settingsFilters
        filters.removeAllViews()
        val entries = settingsSections.flatMap(SettingsSection::entries)
        SettingsFilter.entries.forEach { filter ->
            val count = if (filter == SettingsFilter.ALL) {
                entries.size
            } else {
                entries.count { filter in it.categories }
            }
            val selected = selectedSettingsFilter == filter
            filters.addView(TextView(requireContext()).apply {
                text = "${filter.label}  $count"
                textSize = 12f
                minHeight = dp(42)
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Show ${filter.label.lowercase()} settings, $count items"
                background = if (selected) {
                    GradientDrawable().apply {
                        setColor(themeColor(R.attr.themeAccentLight))
                        cornerRadius = dp(22).toFloat()
                    }
                } else {
                    ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_card)
                }
                setTextColor(
                    themeColor(
                        if (selected) R.attr.themeAccent else R.attr.themeTextPrimary
                    )
                )
                setOnClickListener {
                    selectedSettingsFilter = filter
                    renderSettingsFilters()
                    applySettingsFilter()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun applySettingsFilter() {
        if (_binding == null || settingsSections.isEmpty()) return
        val query = binding.etSettingsSearch.text.toString().trim().lowercase(Locale.ROOT)
        var visibleCount = 0
        settingsSections.forEach { section ->
            section.entries.forEach { entry ->
                val categoryMatches = selectedSettingsFilter == SettingsFilter.ALL ||
                    selectedSettingsFilter in entry.categories
                val searchableText = "${viewText(entry.view)} ${entry.searchAliases}"
                    .lowercase(Locale.ROOT)
                val queryMatches = query.isBlank() || searchableText.contains(query)
                entry.view.visibility = if (categoryMatches && queryMatches) View.VISIBLE else View.GONE
                if (entry.view.visibility == View.VISIBLE) visibleCount++
            }
            section.heading.visibility =
                if (section.entries.any { it.view.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            section.container.visibility =
                if (section.heading.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            updateSettingsDividers(section)
        }
        binding.tvSettingsEmpty.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        binding.tvSettingsEmpty.text = if (query.isBlank()) {
            "No settings in this category."
        } else {
            "No settings match \"$query\"."
        }
    }

    private fun updateSettingsDividers(section: SettingsSection) {
        val rowViews = section.entries.map(SettingEntry::view)
        for (index in 0 until section.container.childCount) {
            val child = section.container.getChildAt(index)
            if (child in rowViews) continue
            val previousRow = (index - 1 downTo 0)
                .map(section.container::getChildAt)
                .firstOrNull { it in rowViews }
            val nextRow = (index + 1 until section.container.childCount)
                .map(section.container::getChildAt)
                .firstOrNull { it in rowViews }
            child.visibility = if (
                previousRow?.visibility == View.VISIBLE &&
                nextRow?.visibility == View.VISIBLE
            ) View.VISIBLE else View.GONE
        }
    }

    private fun viewText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> buildString {
            for (index in 0 until view.childCount) {
                append(viewText(view.getChildAt(index))).append(' ')
            }
        }
        else -> ""
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun updateMeetingModeStatus() {
        val context = requireContext()
        val active = com.jarvis.assistant.meeting.MeetingModePreferences.isActive(context)
        binding.tvMeetingModeStatus.text = if (active) {
            val until = com.jarvis.assistant.meeting.MeetingModePreferences.untilText(context)
            "On \u2022 " + com.jarvis.assistant.meeting.MeetingModePreferences.situation(context).label +
                if (until.isNotEmpty()) " until $until" else ""
        } else {
            "Off \u2022 HOPE declines calls and texts for you"
        }
        binding.tvMeetingModeStatus.setTextColor(
            ContextCompat.getColor(context, if (active) R.color.status_green else R.color.accent_sapphire)
        )
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
        settingsSections = emptyList()
        _binding = null
    }
}
