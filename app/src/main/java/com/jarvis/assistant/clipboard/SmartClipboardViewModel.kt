package com.jarvis.assistant.clipboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SmartClipboardViewModel(
    application: Application,
    private val repository: ClipboardAIRepository,
    private val preferences: AppPreferences
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClipboardUiState())
    val state: StateFlow<ClipboardUiState> = _state.asStateFlow()
    private var processingJob: Job? = null
    private var historyWarning: String? = null

    fun load(content: ClipboardContent, analyzeTextAutomatically: Boolean) {
        if (_state.value.content?.fingerprint == content.fingerprint &&
            (_state.value.isProcessing || _state.value.response != null || _state.value.error != null)
        ) return
        processingJob?.cancel()
        _state.value = ClipboardUiState(
            content = content,
            error = when {
                content.isSensitive -> SENSITIVE_CONTENT_MESSAGE
                content.isTooLarge -> TOO_LARGE_CONTENT_MESSAGE
                else -> null
            },
            orbState = if (content.isSensitive || content.isTooLarge) {
                ClipboardOrbState.ERROR
            } else {
                ClipboardOrbState.CLIPBOARD_READING
            }
        )
        if (content.isSensitive || content.isTooLarge) return
        if (content.type == ClipboardContentType.TEXT &&
            analyzeTextAutomatically &&
            preferences.isSmartClipboardEnabled
        ) {
            analyzeText()
        }
    }

    fun analyzeText(
        explainMore: Boolean = false,
        request: SmartClipboardRequest = SmartClipboardRequest.EXPLAIN
    ) {
        val content = _state.value.content ?: return
        val text = content.text ?: return
        if (content.isSensitive || !preferences.isSmartClipboardEnabled) return
        if (!preferences.isSmartClipboardTextAnalysisEnabled) {
            _state.value = _state.value.copy(
                error = "Text analysis is disabled in Smart Clipboard settings.",
                orbState = ClipboardOrbState.ERROR
            )
            return
        }
        if (content.isTooLarge) {
            _state.value = _state.value.copy(
                error = TOO_LARGE_CONTENT_MESSAGE,
                orbState = ClipboardOrbState.ERROR
            )
            return
        }
        process {
            repository.analyzeText(text, explainMore, request).also { response ->
                if (preferences.isSmartClipboardHistoryEnabled) {
                    if (!preferences.saveSmartClipboardHistoryEntry(text, response, "TEXT")) {
                        historyWarning = "Analysis completed, but encrypted clipboard history could not be saved."
                    }
                }
            }
        }
    }

    fun analyzeImage(
        explainMore: Boolean = false,
        request: SmartClipboardRequest = SmartClipboardRequest.EXPLAIN_IMAGE
    ) {
        val content = _state.value.content ?: return
        val uri = content.imageUri ?: return
        if (!preferences.isSmartClipboardEnabled) {
            _state.value = _state.value.copy(
                error = "Enable Smart Clipboard before analyzing copied content.",
                orbState = ClipboardOrbState.ERROR
            )
            return
        }
        if (!preferences.isSmartClipboardImageAnalysisEnabled) {
            _state.value = _state.value.copy(
                error = "Enable image analysis first. Images are never sent until you explicitly request analysis.",
                orbState = ClipboardOrbState.ERROR
            )
            return
        }
        process {
            repository.analyzeImage(uri, content.mimeType, explainMore, request).also { response ->
                if (preferences.isSmartClipboardHistoryEnabled) {
                    if (!preferences.saveSmartClipboardHistoryEntry("[Copied image]", response, "IMAGE")) {
                        historyWarning = "Analysis completed, but encrypted clipboard history could not be saved."
                    }
                }
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        if (_state.value.isProcessing) {
            _state.value = _state.value.copy(
                isProcessing = false,
                response = null,
                error = "Analysis stopped because HOPE left the foreground.",
                orbState = ClipboardOrbState.ERROR
            )
        }
    }

    fun clear() {
        processingJob?.cancel()
        processingJob = null
        historyWarning = null
        _state.value = ClipboardUiState()
    }

    private fun process(action: suspend () -> String) {
        if (_state.value.isProcessing) return
        processingJob?.cancel()
        historyWarning = null
        _state.value = _state.value.copy(
            response = null,
            error = null,
            isProcessing = true,
            orbState = ClipboardOrbState.ANALYZING
        )
        processingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = action()
                _state.value = _state.value.copy(
                    response = result,
                    error = historyWarning,
                    isProcessing = false,
                    orbState = ClipboardOrbState.RESPONDING
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    response = null,
                    error = error.message ?: "HOPE could not analyze this clipboard content.",
                    isProcessing = false,
                    orbState = ClipboardOrbState.ERROR
                )
            }
        }
    }

    private companion object {
        const val SENSITIVE_CONTENT_MESSAGE =
            "This looks like a password, verification code, or payment credential. HOPE kept it on your device."
        const val TOO_LARGE_CONTENT_MESSAGE =
            "This copied text is longer than 30,000 characters, so HOPE did not send it."
    }
}
