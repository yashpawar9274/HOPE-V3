package com.jarvis.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.GemmaBackend
import com.jarvis.assistant.ai.GemmaModelState
import com.jarvis.assistant.databinding.ActivityGemmaModelBinding
import kotlinx.coroutines.launch
import java.util.Locale

class GemmaModelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGemmaModelBinding
    private val app get() = application as JarvisApp
    private var downloadJobRunning = false
    private var importingModel = false
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityGemmaModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        loadSettings()
        setupActions()
        observeModelState()
    }

    private fun loadSettings() {
        binding.etHuggingFaceToken.setText(app.preferences.huggingFaceToken)
        binding.etAiSystemInstruction.setText(app.preferences.aiSystemInstruction)
        binding.switchCloudInAutomatic.isChecked = app.preferences.cloudAllowedInAutomatic
        when (app.preferences.gemmaBackend) {
            GemmaBackend.CPU -> binding.radioCpu.isChecked = true
            GemmaBackend.GPU -> binding.radioGpu.isChecked = true
        }
        when (app.preferences.aiMode) {
            AiMode.AUTOMATIC -> binding.radioAutomatic.isChecked = true
            AiMode.ONLINE -> binding.radioOnline.isChecked = true
            AiMode.OFFLINE -> binding.radioOffline.isChecked = true
        }
    }

    private fun setupActions() {
        binding.btnGemmaBack.setOnClickListener { finish() }
        binding.btnGemmaTerms.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_TERMS_URL)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No browser is available to open the model page.", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnDownloadGemmaBrowser.setOnClickListener {
            if (!binding.checkGemmaTermsAccepted.isChecked) {
                Toast.makeText(this, "Review and accept the Gemma model terms first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_DOWNLOAD_URL)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No browser is available to open the model download.", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnImportGemma.setOnClickListener {
            if (!binding.checkGemmaTermsAccepted.isChecked) {
                Toast.makeText(this, "Review and accept the Gemma model terms first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            modelPicker.launch(arrayOf("*/*"))
        }
        binding.btnDownloadGemma.setOnClickListener { startDownload() }
        binding.btnPauseGemma.setOnClickListener {
            app.gemmaModelManager.pause()
        }
        binding.btnLoadGemma.setOnClickListener {
            lifecycleScope.launch {
                binding.btnLoadGemma.isEnabled = false
                binding.tvGemmaLoadError.visibility = View.GONE
                val requestedBackend = app.preferences.gemmaBackend
                try {
                    app.gemmaTextGeneration.load()
                    if (requestedBackend == GemmaBackend.GPU && app.preferences.gemmaBackend == GemmaBackend.CPU) {
                        binding.radioCpu.isChecked = true
                        binding.radioGpu.isChecked = false
                        binding.tvGemmaLoadError.visibility = View.VISIBLE
                        binding.tvGemmaLoadError.text =
                            "GPU initialization failed on this device. HOPE loaded Gemma with the compatible CPU backend instead."
                    } else {
                        Toast.makeText(this@GemmaModelActivity, "Gemma loaded and ready.", Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    binding.tvGemmaLoadError.visibility = View.VISIBLE
                    binding.tvGemmaLoadError.text = "Could not load Gemma: ${errorSummary(error)}"
                    android.util.Log.e(TAG, "Gemma model failed to initialize.", error)
                } catch (error: OutOfMemoryError) {
                    binding.tvGemmaLoadError.visibility = View.VISIBLE
                    binding.tvGemmaLoadError.text =
                        "This device ran out of memory while loading Gemma. Close other apps, restart the phone, then try Load Gemma again."
                    android.util.Log.e(TAG, "Insufficient memory while loading Gemma.", error)
                } finally {
                    binding.btnLoadGemma.isEnabled = true
                    renderModelState(app.gemmaModelManager.state.value)
                }
            }
        }
        binding.btnUnloadGemma.setOnClickListener {
            lifecycleScope.launch {
                binding.btnUnloadGemma.isEnabled = false
                try {
                    app.gemmaTextGeneration.unload()
                    Toast.makeText(this@GemmaModelActivity, "Gemma unloaded from memory.", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(
                        this@GemmaModelActivity,
                        error.message ?: "Gemma could not be unloaded.",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    binding.btnUnloadGemma.isEnabled = true
                    renderModelState(app.gemmaModelManager.state.value)
                }
            }
        }
        binding.btnDeleteGemma.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete Gemma?")
                .setMessage("This removes the local model and any incomplete download. You can download it again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        binding.btnDeleteGemma.isEnabled = false
                        try {
                            app.gemmaTextGeneration.unload()
                            app.gemmaModelManager.deleteModel()
                            Toast.makeText(this@GemmaModelActivity, "Local Gemma files deleted.", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@GemmaModelActivity,
                                error.message ?: "Could not remove the model.",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            binding.btnDeleteGemma.isEnabled = true
                        }
                    }
                }
                .show()
        }
        binding.btnSaveAiSettings.setOnClickListener { saveSettings() }
    }

    private fun startDownload() {
        if (!binding.checkGemmaTermsAccepted.isChecked) {
            Toast.makeText(this, "Review and accept the Gemma model terms first.", Toast.LENGTH_LONG).show()
            return
        }
        val token = binding.etHuggingFaceToken.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            binding.etHuggingFaceToken.error = "A Hugging Face read token is required for this gated model."
            return
        }
        try {
            app.preferences.huggingFaceToken = token
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "Secure token storage is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        if (downloadJobRunning) return
        downloadJobRunning = true
        lifecycleScope.launch {
            binding.btnDownloadGemma.isEnabled = false
            try {
                app.gemmaModelManager.download(token)
            } catch (error: Exception) {
                Toast.makeText(
                    this@GemmaModelActivity,
                    error.message ?: "The model download failed.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                downloadJobRunning = false
                renderModelState(app.gemmaModelManager.state.value)
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (downloadJobRunning) return
        downloadJobRunning = true
        importingModel = true
        lifecycleScope.launch {
            binding.btnImportGemma.isEnabled = false
            try {
                app.gemmaModelManager.importModel(uri)
                Toast.makeText(this@GemmaModelActivity, "Model verified and imported. Load Gemma to use it.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(
                    this@GemmaModelActivity,
                    error.message ?: "The selected model could not be imported.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                importingModel = false
                downloadJobRunning = false
                binding.btnImportGemma.isEnabled = true
                renderModelState(app.gemmaModelManager.state.value)
            }
        }
    }

    private fun saveSettings() {
        val systemInstruction = binding.etAiSystemInstruction.text?.toString()?.trim().orEmpty()
        if (systemInstruction.isBlank()) {
            binding.etAiSystemInstruction.error = "Enter a system instruction."
            return
        }
        if (systemInstruction.length > MAX_SYSTEM_INSTRUCTION_LENGTH) {
            binding.etAiSystemInstruction.error = "System instruction must be 4,000 characters or fewer."
            return
        }
        val newBackend = if (binding.radioGpu.isChecked) GemmaBackend.GPU else GemmaBackend.CPU
        lifecycleScope.launch {
            if (app.preferences.gemmaBackend != newBackend && app.gemmaTextGeneration.isLoaded) {
                try {
                    app.gemmaTextGeneration.unload()
                } catch (error: Exception) {
                    Toast.makeText(
                        this@GemmaModelActivity,
                        error.message ?: "Could not unload Gemma to change its backend.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }
            app.preferences.aiMode = when (binding.radioAiMode.checkedRadioButtonId) {
            R.id.radioOnline -> AiMode.ONLINE
            R.id.radioOffline -> AiMode.OFFLINE
            else -> AiMode.AUTOMATIC
            }
            app.preferences.cloudAllowedInAutomatic = binding.switchCloudInAutomatic.isChecked
            app.preferences.gemmaBackend = newBackend
            app.preferences.aiSystemInstruction = systemInstruction
            Toast.makeText(this@GemmaModelActivity, "AI routing settings saved.", Toast.LENGTH_SHORT).show()
            renderModelState(app.gemmaModelManager.state.value)
        }
    }

    private fun observeModelState() {
        lifecycleScope.launch {
            app.gemmaModelManager.state.collect(::renderModelState)
        }
    }

    private fun renderModelState(state: GemmaModelState) {
        val loaded = app.gemmaTextGeneration.isLoaded
        when (state) {
            GemmaModelState.Missing -> {
                binding.tvGemmaModelState.text = "Model is not downloaded."
                binding.progressGemmaDownload.visibility = View.GONE
            }
            is GemmaModelState.Downloading -> {
                val percent = if (state.totalBytes > 0) {
                    (state.downloadedBytes * 1000 / state.totalBytes).toInt().coerceIn(0, 1000)
                } else 0
                binding.tvGemmaModelState.text =
                    "Downloading ${formatMiB(state.downloadedBytes)} / ${formatMiB(state.totalBytes)} MiB"
                binding.progressGemmaDownload.visibility = View.VISIBLE
                binding.progressGemmaDownload.progress = percent
            }
            GemmaModelState.Paused -> {
                binding.tvGemmaModelState.text = "Download paused. Select Download / Resume to continue."
                binding.progressGemmaDownload.visibility = View.VISIBLE
            }
            GemmaModelState.Ready -> {
                binding.tvGemmaModelState.text = if (loaded) {
                    "Model installed • integrity verified and loaded"
                } else {
                    "Model present • SHA-256 is checked before loading"
                }
                binding.progressGemmaDownload.visibility = View.GONE
            }
            is GemmaModelState.Failed -> {
                binding.tvGemmaModelState.text = "Model error: ${state.message}"
                binding.progressGemmaDownload.visibility = View.GONE
            }
        }
        binding.btnPauseGemma.visibility =
            if (state is GemmaModelState.Downloading && !importingModel) View.VISIBLE else View.GONE
        binding.btnLoadGemma.isEnabled =
            state == GemmaModelState.Ready && !loaded
        binding.btnUnloadGemma.isEnabled = loaded
        binding.btnDeleteGemma.isEnabled =
            app.gemmaModelManager.hasModelFiles || app.gemmaTextGeneration.isLoaded
        if (!downloadJobRunning) {
            binding.btnDownloadGemma.isEnabled = state !is GemmaModelState.Downloading
        }
    }

    private fun formatMiB(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))

    private fun errorSummary(error: Throwable): String {
        val messages = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(3)
            .toList()
        return messages.joinToString(" — ").ifBlank { error.javaClass.simpleName }
    }

    override fun onDestroy() {
        if (downloadJobRunning) app.gemmaModelManager.pause()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "GemmaModelActivity"
        const val MODEL_TERMS_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT"
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/d448835d84411a08f577e25f3afebe528402d73b/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
        const val MAX_SYSTEM_INSTRUCTION_LENGTH = 4000
    }
}
