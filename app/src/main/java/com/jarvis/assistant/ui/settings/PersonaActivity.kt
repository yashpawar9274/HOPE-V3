package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.PersonaEngine
import com.jarvis.assistant.ai.VoicePersonaRegistry
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityPersonaBinding
import com.jarvis.assistant.util.ThemeManager

class PersonaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var currentSelectedPersona = PersonaEngine.HOPE
    private val voiceSelectionsByPersona = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadInitialState()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadInitialState() {
        val currentPersona = PersonaEngine.profileFor(preferences.personality)
        val savedVoice = preferences.voice.takeIf { it in GeminiConstants.SUPPORTED_VOICES }
            ?: currentPersona.defaultVoiceName
        preferences.rememberPersonaVoiceIfUnset(currentPersona.id, savedVoice)
        selectPersona(currentPersona.id, updateVoiceDefault = false)

        binding.etUserName.setText(preferences.userName.ifBlank { "YASH" })

        populateVoiceChoices()
        val personaVoice = preferences.personaVoice(currentPersona.id)
        voiceSelectionsByPersona[currentPersona.id] = personaVoice
        selectVoice(personaVoice)
        binding.switchAutomaticPersona.isChecked = preferences.isAutomaticPersonaSwitchingEnabled
        updatePersonaPreview()
        binding.scrollPersona.post { binding.scrollPersona.scrollTo(0, 0) }
    }

    private fun setupListeners() {
        binding.etUserName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePersonaPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.cardPersonaJarvis.setOnClickListener {
            selectPersona(PersonaEngine.HOPE, updateVoiceDefault = true)
        }

        binding.cardPersonaGirlfriend.setOnClickListener {
            selectPersona(PersonaEngine.FRIDAY, updateVoiceDefault = true)
        }

        binding.cardPersonaBoyfriend.setOnClickListener {
            selectPersona(PersonaEngine.EDITH, updateVoiceDefault = true)
        }

        binding.cardPersonaTechGpt.setOnClickListener {
            selectPersona(PersonaEngine.ULTRON, updateVoiceDefault = true)
        }

        binding.btnCustomizePersona.setOnClickListener { showCustomizePersonaDialog() }
        binding.switchAutomaticPersona.setOnCheckedChangeListener { _, enabled ->
            preferences.isAutomaticPersonaSwitchingEnabled = enabled
            Toast.makeText(
                this,
                if (enabled) "Automatic expertise adaptation enabled" else "Selected voice persona will be used for every request",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.rgVoiceGroup.setOnCheckedChangeListener { _, _ -> updatePersonaPreview() }

        binding.btnActivatePersona.setOnClickListener {
            saveAndActivate()
        }
    }

    private fun populateVoiceChoices() {
        binding.rgVoiceGroup.removeAllViews()
        VoicePersonaRegistry.profiles.forEachIndexed { index, profile ->
            if (index > 0) {
                binding.rgVoiceGroup.addView(View(this).apply {
                    setBackgroundResource(com.jarvis.assistant.R.color.border_subtle)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    )
                })
            }
            binding.rgVoiceGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = profile.voiceName
                text = "${profile.voiceName} — ${profile.personaName.substringAfter("—").trim()}\n${profile.primaryExpertise}"
                textSize = 13f
                setPadding(
                    (resources.displayMetrics.density * 4).toInt(),
                    (resources.displayMetrics.density * 8).toInt(),
                    (resources.displayMetrics.density * 4).toInt(),
                    (resources.displayMetrics.density * 8).toInt()
                )
            })
        }
    }

    private fun selectPersona(personaKey: String, updateVoiceDefault: Boolean) {
        if (updateVoiceDefault && currentSelectedPersona != personaKey &&
            ::binding.isInitialized && binding.rgVoiceGroup.childCount > 0
        ) {
            voiceSelectionsByPersona[currentSelectedPersona] = getSelectedVoice()
        }
        currentSelectedPersona = personaKey

        binding.rbSelectJarvis.isChecked = (personaKey == PersonaEngine.HOPE)
        binding.rbSelectGirlfriend.isChecked = (personaKey == PersonaEngine.FRIDAY)
        binding.rbSelectBoyfriend.isChecked = (personaKey == PersonaEngine.EDITH)
        binding.rbSelectTechGpt.isChecked = (personaKey == PersonaEngine.ULTRON)

        if (updateVoiceDefault) {
            val selectedVoice = voiceSelectionsByPersona[personaKey] ?: preferences.personaVoice(personaKey)
            selectVoice(selectedVoice)
        }
        if (::binding.isInitialized) updatePersonaPreview()
    }

    private fun updatePersonaPreview() {
        if (!::binding.isInitialized) return
        val persona = PersonaEngine.profileFor(currentSelectedPersona)
        val operator = binding.etUserName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "YASH"
        val profile = VoicePersonaRegistry.profileForVoice(getSelectedVoice())
        binding.tvPersonaPreview.text = "${persona.displayName} · ${persona.identityName} · ${profile.voiceName} voice"
        binding.tvPersonaGreeting.text =
            "${persona.responseStyle}. Ready to help, $operator."
    }

    private fun showCustomizePersonaDialog() {
        val voice = getSelectedVoice()
        val profile = VoicePersonaRegistry.profileForVoice(voice)
        val fields = listOf(
            Triple("Persona name", "name", preferences.getVoicePersonaCustomization(voice, "name")),
            Triple("Primary expertise", "expertise", preferences.getVoicePersonaCustomization(voice, "expertise")),
            Triple("Communication tone", "tone", preferences.getVoicePersonaCustomization(voice, "tone"))
        )
        val inputs = fields.map { (label, _, currentValue) ->
            EditText(this).apply {
                hint = label
                setText(currentValue)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((resources.displayMetrics.density * 24).toInt())
            inputs.forEachIndexed { index, input ->
                input.hint = "${fields[index].first} (default: ${
                    when (fields[index].second) {
                        "name" -> profile.personaName
                        "expertise" -> profile.primaryExpertise
                        else -> profile.communicationStyle
                    }
                })"
                addView(input)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Customize $voice persona")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                fields.forEachIndexed { index, field ->
                    preferences.setVoicePersonaCustomization(voice, field.second, inputs[index].text.toString())
                }
                updatePersonaPreview()
                Toast.makeText(this, "$voice persona saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                fields.forEach { (_, field, _) ->
                    preferences.setVoicePersonaCustomization(voice, field, "")
                }
                updatePersonaPreview()
                Toast.makeText(this, "$voice persona restored to default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectVoice(voiceName: String) {
        val matching = (0 until binding.rgVoiceGroup.childCount)
            .mapNotNull { binding.rgVoiceGroup.getChildAt(it) as? RadioButton }
            .firstOrNull { (it.tag as? String).equals(voiceName, ignoreCase = true) }
        val default = (0 until binding.rgVoiceGroup.childCount)
            .mapNotNull { binding.rgVoiceGroup.getChildAt(it) as? RadioButton }
            .firstOrNull { (it.tag as? String) == GeminiConstants.DEFAULT_VOICE }
        (matching ?: default)?.isChecked = true
    }

    private fun getSelectedVoice(): String {
        return binding.rgVoiceGroup.findViewById<RadioButton>(binding.rgVoiceGroup.checkedRadioButtonId)
            ?.tag as? String ?: GeminiConstants.DEFAULT_VOICE
    }

    private fun saveAndActivate() {
        val userName = binding.etUserName.text?.toString()?.trim()
        if (!userName.isNullOrBlank()) {
            preferences.userName = userName
        } else {
            preferences.userName = "YASH"
        }

        val voice = getSelectedVoice()
        val personaOrVoiceChanged =
            preferences.personality != currentSelectedPersona || preferences.voice != voice
        voiceSelectionsByPersona[currentSelectedPersona] = voice
        voiceSelectionsByPersona.forEach { (personaId, selectedVoice) ->
            preferences.setPersonaVoice(personaId, selectedVoice)
        }
        preferences.voice = voice
        preferences.personality = currentSelectedPersona
        if (personaOrVoiceChanged && JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.restartSession()
        }

        val personaDisplayName = PersonaEngine.profileFor(currentSelectedPersona).displayName

        Toast.makeText(
            this,
            "Activated: $personaDisplayName with $voice Voice",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
