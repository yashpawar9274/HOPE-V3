package com.jarvis.assistant.autoreply

import android.speech.tts.TextToSpeech
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

class HopeNotificationListenerService : NotificationListenerService() {
    private var textToSpeech: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        textToSpeech = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            NotificationIntelligenceProcessor.process(applicationContext, sbn, ::announce)
        } catch (error: Exception) {
            Log.e(TAG, "Could not process a posted notification.", error)
        }
    }

    private fun announce(text: String, language: String) {
        if (!NotificationIntelligenceStore.areAnnouncementsEnabled(this) || !ttsReady) return
        val locale = when (language) {
            "Hindi", "Hinglish" -> Locale("hi", "IN")
            "English" -> Locale.US
            else -> if (Regex("""[\u0900-\u097F]""").containsMatchIn(text)) Locale("hi", "IN") else Locale.US
        }
        val engine = textToSpeech ?: return
        val availability = engine.isLanguageAvailable(locale)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "Configured announcement language is not available in the installed TTS engine.")
            return
        }
        engine.language = locale
        engine.speak(text.take(1000), TextToSpeech.QUEUE_ADD, null, "notification-${System.currentTimeMillis()}")
    }

    override fun onDestroy() {
        ttsReady = false
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "HopeNotificationListener"
    }
}
