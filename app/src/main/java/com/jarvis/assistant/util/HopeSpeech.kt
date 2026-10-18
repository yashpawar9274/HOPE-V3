package com.jarvis.assistant.util

import android.speech.tts.TextToSpeech
import java.util.Locale

object HopeSpeech {
    fun configureLanguage(engine: TextToSpeech, language: String, text: String): Boolean {
        val locale = preferredLocale(language, text)
        if (engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) return true
        if (locale != Locale.US && engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE) return true
        return false
    }

    fun preferredLocale(language: String, text: String): Locale = when (language) {
        "Hindi", "Hinglish" -> Locale("hi", "IN")
        "English" -> Locale.US
        else -> if (
            Regex("""[\u0900-\u097F]""").containsMatchIn(text) ||
            Locale.getDefault().language.equals("hi", ignoreCase = true)
        ) {
            Locale("hi", "IN")
        } else {
            Locale.US
        }
    }
}
