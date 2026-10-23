package com.jarvis.assistant.ui.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.SpeakerProfile
import com.jarvis.assistant.audio.SpeakerProfileData
import com.jarvis.assistant.audio.SpeakerProfileStore
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class SpeakerProfilesActivity : AppCompatActivity() {

    private enum class EditorMode { ADD, RETRAIN, RENAME }

    private lateinit var content: LinearLayout
    private lateinit var store: SpeakerProfileStore
    private var profileData = SpeakerProfileData()
    private var loadError: String? = null
    private var pendingAuthenticatedAction: (() -> Unit)? = null
    private var editorMode: EditorMode? = null
    private var editingProfileId: String? = null
    private var nameField: EditText? = null
    private var addressField: EditText? = null
    private var languageField: EditText? = null
    private var consentCheck: CheckBox? = null
    private var sampleStatus: TextView? = null
    private var recordButton: MaterialButton? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingBuffer = WipingByteArrayOutputStream()
    private val recordedEmbeddings = mutableListOf<FloatArray>()
    private var sampleIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val legacySystemAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) pendingAuthenticatedAction?.invoke()
        else toast("Android authentication was cancelled; profile data was not changed.")
        pendingAuthenticatedAction = null
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSampleRecording()
        else toast("Allow microphone access in Android settings to record enrollment samples.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = SpeakerProfileStore(applicationContext)
        loadProfiles()

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ThemeManager.applyDynamicBackground(scroll)
        setContentView(scroll)
        render()

        VoskModelManager.init(applicationContext, lifecycleScope, includeSpeakerModel = true)
    }

    override fun onStop() {
        val wasRecording = isRecording
        stopAudioCapture()
        if (wasRecording) {
            sampleStatus?.text = "Recording stopped when HOPE left the screen. The unfinished sample was discarded."
            recordButton?.text = "Record sample ${sampleIndex + 1}"
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopAudioCapture()
        recordingBuffer.reset()
        recordedEmbeddings.forEach { it.fill(0f) }
        recordedEmbeddings.clear()
        super.onDestroy()
    }

    private fun loadProfiles() {
        try {
            profileData = store.read()
            loadError = null
        } catch (error: Exception) {
            profileData = SpeakerProfileData()
            loadError = error.message ?: "Protected speaker-profile storage is unavailable."
            com.jarvis.assistant.audio.SpeakerProfileState.publish(profileData)
        }
    }

    private fun render() {
        content.removeAllViews()
        nameField = null
        addressField = null
        languageField = null
        consentCheck = null
        sampleStatus = null
        recordButton = null

        addText("SPEAKER PROFILES", 12f, true, R.attr.themeAccent, 8)
        addText("Personalized addressing", 24f, true, R.attr.themeTextPrimary, 8)
        addText(
            "HOPE uses an on-device Vosk/Kaldi x-vector speaker model. Voice matching is probabilistic, can fail under noise or replay, and must never authorize security, payment, or device-unlock actions.",
            14f, false, R.attr.themeTextMuted, 14
        )
        addText(
            "Raw enrollment audio is discarded after each sample. Encrypted voice embeddings and profile details stay on this device. When personalization is enabled, the selected preferred address and language are sent as context to HOPE's configured AI service; normal active voice audio follows the existing voice-service settings.",
            13f, false, R.attr.themeTextMuted, 16
        )

        if (loadError != null) {
            addText("Profiles could not be loaded: $loadError", 14f, true, R.attr.themeTextPrimary, 8)
            addButton("Retry loading") {
                loadProfiles()
                render()
            }
            return
        }

        val enableSwitch = MaterialSwitch(this).apply {
            text = "Enable personalized addressing"
            isChecked = profileData.enabled && profileData.profiles.isNotEmpty()
            isEnabled = profileData.profiles.isNotEmpty()
            setOnCheckedChangeListener { _, checked ->
                try {
                    profileData = profileData.copy(enabled = checked)
                    store.write(profileData)
                    toast(if (checked) "Speaker recognition enabled for personalization." else "Speaker recognition paused.")
                } catch (error: Exception) {
                    loadProfiles()
                    isChecked = profileData.enabled
                    toast(error.message ?: "Could not save speaker settings.")
                }
            }
        }
        content.addView(enableSwitch, verticalParams(bottom = 10))

        val thresholdLabel = addText("Matching threshold: ${"%.2f".format(Locale.ROOT, profileData.distanceThreshold)} cosine distance", 14f, true, R.attr.themeTextPrimary, 2)
        val slider = Slider(this).apply {
            valueFrom = com.jarvis.assistant.audio.SpeakerProfileMatcher.MIN_THRESHOLD
            valueTo = com.jarvis.assistant.audio.SpeakerProfileMatcher.MAX_THRESHOLD
            stepSize = 0.01f
            value = profileData.distanceThreshold
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    profileData = profileData.copy(distanceThreshold = value)
                    thresholdLabel.text =
                        "Matching threshold: ${"%.2f".format(Locale.ROOT, value)} cosine distance"
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) {
                    try {
                        store.write(profileData)
                    } catch (error: Exception) {
                        loadProfiles()
                        render()
                        toast(error.message ?: "Could not save the matching threshold.")
                    }
                }
            })
        }
        content.addView(slider, verticalParams(bottom = 4))
        addText("Lower is stricter. Close matches are treated as UNKNOWN.", 12f, false, R.attr.themeTextMuted, 16)

        if (profileData.profiles.isEmpty()) {
            addText("No speaker profiles are enrolled. Unknown speakers are always addressed neutrally.", 14f, false, R.attr.themeTextMuted, 12)
        }
        profileData.profiles.forEach(::addProfileCard)

        addButton("Add consenting speaker") {
            if (profileData.profiles.size >= SpeakerProfileStore.MAX_PROFILES) {
                toast("You can enroll up to ${SpeakerProfileStore.MAX_PROFILES} speakers.")
            } else authenticateAndRun { beginEditor(EditorMode.ADD, null) }
        }

        editorMode?.let(::renderEditor)
    }

    private fun addProfileCard(profile: SpeakerProfile) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_jarvis_card)
        }
        val title = profile.preferredAddress.ifBlank { profile.preferredName }
        val summary = "$title • ${profile.preferredName}\n${profile.language} • ${profile.embeddings.size} local samples"
        card.addView(TextView(this).apply {
            text = summary
            textSize = 15f
            setTextColor(resolveColor(R.attr.themeTextPrimary))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("Rename") {
            authenticateAndRun { beginEditor(EditorMode.RENAME, profile) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        actions.addView(actionButton("Retrain") {
            MaterialAlertDialogBuilder(this@SpeakerProfilesActivity)
                .setTitle("Replace this voice profile?")
                .setMessage("The current voice embeddings will be replaced only after the consenting speaker completes all three new samples.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    authenticateAndRun { beginEditor(EditorMode.RETRAIN, profile) }
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actions.addView(actionButton("Delete") {
            MaterialAlertDialogBuilder(this@SpeakerProfilesActivity)
                .setTitle("Delete voice profile?")
                .setMessage("This permanently removes the encrypted voice embeddings and profile details from HOPE.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    authenticateAndRun {
                        try {
                            val remaining = profileData.profiles.filterNot { it.id == profile.id }
                            if (remaining.isEmpty()) store.deleteAll()
                            else store.write(profileData.copy(profiles = remaining))
                            loadProfiles()
                            render()
                        } catch (error: Exception) {
                            loadProfiles()
                            render()
                            toast(error.message ?: "Could not delete the voice profile.")
                        }
                    }
                }
                .show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        card.addView(actions, verticalParams(top = 10))
        content.addView(card, verticalParams(bottom = 12))
    }

    private fun beginEditor(mode: EditorMode, profile: SpeakerProfile?) {
        editorMode = mode
        editingProfileId = profile?.id
        sampleIndex = 0
        recordedEmbeddings.forEach { it.fill(0f) }
        recordedEmbeddings.clear()
        render()
    }

    private fun renderEditor(mode: EditorMode) {
        addText(
            when (mode) {
                EditorMode.ADD -> "Enroll a consenting speaker"
                EditorMode.RETRAIN -> "Retrain speaker profile"
                EditorMode.RENAME -> "Edit speaker details"
            },
            18f, true, R.attr.themeTextPrimary, 10
        )
        val profile = profileData.profiles.firstOrNull { it.id == editingProfileId }
        nameField = addInput("Preferred name", profile?.preferredName.orEmpty())
        addressField = addInput("Preferred form of address (e.g. Sir, Ji Ma'am, or name)", profile?.preferredAddress.orEmpty())
        languageField = addInput("Preferred language (e.g. English, Hindi, Hinglish)", profile?.language ?: "English")

        if (mode != EditorMode.RENAME) {
            addText(
                "Before recording, the speaker must agree. Record in a quiet place, speak naturally for at least two seconds, and repeat each prompt. Only encrypted embeddings are retained; raw audio is discarded. The first spoken prompt is an explicit consent statement.",
                13f, false, R.attr.themeTextMuted, 8
            )
            consentCheck = CheckBox(this).apply {
                text = "The speaker has read this notice and consents to local voice-profile processing."
                setTextColor(resolveColor(R.attr.themeTextPrimary))
            }
            content.addView(consentCheck, verticalParams(bottom = 8))
            sampleStatus = addText("Sample 1 of 3: “I consent to HOPE creating my voice profile on this device.”", 14f, true, R.attr.themeAccent, 8)
            recordButton = actionButton("Record sample 1") {
                if (isRecording) stopAndProcessSample() else requestOrStartRecording()
            }
            content.addView(recordButton, verticalParams(bottom = 8))
        }

        addButton(if (mode == EditorMode.RENAME) "Save profile details" else "Save after all three samples") {
            if (mode == EditorMode.RENAME) saveRename()
            else saveEnrolledProfile()
        }.also {
            it.isEnabled = mode == EditorMode.RENAME
            it.tag = SAVE_BUTTON_TAG
        }
        addButton("Cancel") {
            stopAudioCapture()
            recordedEmbeddings.forEach { it.fill(0f) }
            recordedEmbeddings.clear()
            editorMode = null
            editingProfileId = null
            render()
        }
    }

    private fun saveRename() {
        val old = profileData.profiles.firstOrNull { it.id == editingProfileId } ?: return
        val updated = editedMetadata(old.id, old.embeddings) ?: return
        try {
            store.write(profileData.copy(profiles = profileData.profiles.map {
                if (it.id == old.id) updated else it
            }))
            closeEditorAndReload()
        } catch (error: Exception) {
            toast(error.message ?: "Could not save speaker details.")
        }
    }

    private fun saveEnrolledProfile() {
        if (recordedEmbeddings.size != SpeakerProfileStore.MIN_SAMPLES) {
            toast("Complete all three enrollment samples before saving.")
            return
        }
        if (consentCheck?.isChecked != true) {
            toast("Confirm that the speaker explicitly consents before saving.")
            return
        }
        val previous = profileData.profiles.firstOrNull { it.id == editingProfileId }
        val id = previous?.id ?: SpeakerProfileStore.newProfileId()
        val updated = editedMetadata(id, recordedEmbeddings.map { it.copyOf() }) ?: return
        try {
            val profiles = if (previous == null) profileData.profiles + updated
            else profileData.profiles.map { if (it.id == previous.id) updated else it }
            store.write(profileData.copy(profiles = profiles))
            recordedEmbeddings.forEach { it.fill(0f) }
            recordedEmbeddings.clear()
            closeEditorAndReload()
        } catch (error: Exception) {
            updated.embeddings.forEach { it.fill(0f) }
            toast(error.message ?: "Could not save the encrypted speaker profile.")
        }
    }

    private fun editedMetadata(id: String, embeddings: List<FloatArray>): SpeakerProfile? {
        val name = nameField?.text?.toString()?.trim().orEmpty()
        val address = addressField?.text?.toString()?.trim().orEmpty()
        val language = languageField?.text?.toString()?.trim().orEmpty()
        if (name.isBlank() || name.length > 50 || address.isBlank() || address.length > 32 ||
            language.isBlank() || language.length > 40 ||
            !name.matches(SAFE_LABEL) || !address.matches(SAFE_LABEL) || !language.matches(SAFE_LABEL)
        ) {
            toast("Use letters, spaces, commas, periods, apostrophes, or hyphens for profile details.")
            return null
        }
        return SpeakerProfile(id, name, address, language, embeddings)
    }

    private fun closeEditorAndReload() {
        editorMode = null
        editingProfileId = null
        loadProfiles()
        render()
        toast("Speaker profile saved on this device.")
    }

    private fun requestOrStartRecording() {
        if (consentCheck?.isChecked != true) {
            toast("The speaker must consent before any sample is recorded.")
            return
        }
        if (!VoskModelManager.isSpeakerModelReady.value) {
            toast("The on-device speaker model is still loading. Try again shortly.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (sampleIndex == 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Confirm speaker consent")
                .setMessage("Ask the consenting speaker to read the displayed consent statement and all three samples. Audio is processed on this device and discarded after embeddings are created.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ -> startSampleRecording() }
                .show()
        } else {
            startSampleRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSampleRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!VoskModelManager.isSpeakerModelReady.value) {
            toast("The local speaker model is not ready. Enrollment has not started.")
            return
        }
        synchronized(recordingBuffer) { recordingBuffer.reset() }
        isRecording = true
        recordButton?.text = "Stop and process sample"
        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) {
                mainHandler.post { onRecordingError("Android could not initialize microphone audio.") }
                return@launch
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, 32_000)
                )
            } catch (error: Exception) {
                mainHandler.post { onRecordingError("Microphone recording could not start.") }
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                mainHandler.post { onRecordingError("Android did not initialize microphone recording.") }
                return@launch
            }
            audioRecord = record
            try {
                record.startRecording()
                val chunk = ByteArray(640)
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) synchronized(recordingBuffer) {
                        if (recordingBuffer.size() + read <= MAX_SAMPLE_BYTES) {
                            recordingBuffer.write(chunk, 0, read)
                        } else {
                            mainHandler.post { onRecordingError("Sample reached the safe recording limit. Please retry.") }
                            break
                        }
                    }
                }
            } catch (error: Exception) {
                mainHandler.post { onRecordingError("Microphone recording ended unexpectedly.") }
            } finally {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                } catch (_: Exception) { }
                record.release()
                if (audioRecord === record) audioRecord = null
            }
        }
        sampleStatus?.text = "Recording sample ${sampleIndex + 1}…"
    }

    private fun stopAndProcessSample() {
        if (!isRecording) return
        isRecording = false
        recordButton?.isEnabled = false
        val activeJob = recordingJob
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        lifecycleScope.launch {
            activeJob?.cancelAndJoin()
            recordingJob = null
            recordButton?.isEnabled = true
            val pcm = synchronized(recordingBuffer) {
                recordingBuffer.toByteArray().also { recordingBuffer.reset() }
            }
            if (pcm.size < MIN_SAMPLE_BYTES) {
                pcm.fill(0)
                recordButton?.text = "Retry sample ${sampleIndex + 1}"
                sampleStatus?.text = "Too short. Speak the full prompt for at least two seconds."
                return@launch
            }
            recordButton?.text = "Creating on-device embedding…"
            val vector = withContext(Dispatchers.Default) {
                try {
                    OwnerVoiceManager.extractSpeakerVector(pcm)
                } finally {
                    pcm.fill(0)
                }
            }
            if (vector == null || !OwnerVoiceManager.isValidVector(vector)) {
                vector?.fill(0f)
                recordButton?.text = "Retry sample ${sampleIndex + 1}"
                sampleStatus?.text = "No reliable speaker embedding was produced. Retry in a quieter room."
                return@launch
            }
            recordedEmbeddings.add(vector)
            sampleIndex++
            if (sampleIndex == SpeakerProfileStore.MIN_SAMPLES) {
                sampleStatus?.text = "All three samples are ready. Review the details, then save."
                recordButton?.text = "All samples recorded"
                recordButton?.isEnabled = false
                content.findViewWithTag<MaterialButton>(SAVE_BUTTON_TAG)?.isEnabled = true
            } else {
                sampleStatus?.text = "Sample $sampleIndex of 3 complete. Next: ${samplePrompt(sampleIndex)}"
                recordButton?.text = "Record sample ${sampleIndex + 1}"
            }
        }
    }

    private fun samplePrompt(index: Int): String = when (index) {
        0 -> "I consent to HOPE creating my voice profile on this device."
        1 -> "HOPE, please tell me what you can help me with today."
        else -> "I am speaking naturally so HOPE can recognize my voice."
    }

    private fun onRecordingError(message: String) {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        synchronized(recordingBuffer) { recordingBuffer.reset() }
        sampleStatus?.text = message
        recordButton?.text = "Retry sample ${sampleIndex + 1}"
        toast(message)
    }

    private fun stopAudioCapture() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        recordingJob?.cancel()
        recordingJob = null
        audioRecord = null
        synchronized(recordingBuffer) { recordingBuffer.reset() }
    }

    private fun authenticateAndRun(action: () -> Unit) {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!keyguard.isDeviceSecure) {
            toast("Set a secure Android screen lock before adding, replacing, renaming, or deleting speaker profiles.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingAuthenticatedAction = action
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val accepted = pendingAuthenticatedAction
                        pendingAuthenticatedAction = null
                        accepted?.invoke()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        pendingAuthenticatedAction = null
                        toast("Android authentication was not completed; profile data was not changed.")
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm profile management")
                .setSubtitle("Authenticate with Android before changing local speaker profiles.")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        } else {
            val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(
                "Confirm profile management",
                "Authenticate with your Android screen lock to continue."
            ) ?: run {
                toast("Android could not provide a secure authentication screen.")
                return
            }
            pendingAuthenticatedAction = action
            legacySystemAuthLauncher.launch(intent)
        }
    }

    private fun addButton(label: String, action: () -> Unit): MaterialButton =
        actionButton(label, action).also { content.addView(it, verticalParams(bottom = 8)) }

    private fun actionButton(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun addInput(hintText: String, value: String): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setSingleLine(true)
            setTextColor(resolveColor(R.attr.themeTextPrimary))
            setHintTextColor(resolveColor(R.attr.themeTextMuted))
            content.addView(this, verticalParams(bottom = 8))
        }

    private fun addText(
        value: String,
        size: Float,
        bold: Boolean,
        colorAttribute: Int,
        bottom: Int
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(resolveColor(colorAttribute))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        content.addView(this, verticalParams(bottom = bottom))
    }

    private fun verticalParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        @Synchronized
        override fun reset() {
            buf.fill(0)
            super.reset()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MIN_SAMPLE_BYTES = SAMPLE_RATE * 2 * 2
        private const val MAX_SAMPLE_BYTES = SAMPLE_RATE * 2 * 12
        private const val SAVE_BUTTON_TAG = "speaker_profile_save"
        private val SAFE_LABEL = Regex("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .,\\u2019'\\-]*")
    }
}
