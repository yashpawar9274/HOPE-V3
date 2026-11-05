package com.jarvis.assistant.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required companion of a voice-interaction service on some Android versions. HOPE does its own
 * speech handling (Gemini Live / Vosk), so this service just reports that it cannot transcribe and
 * is deliberately NOT registered as a system speech-recognition provider.
 */
class HopeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
            // The caller already went away.
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
