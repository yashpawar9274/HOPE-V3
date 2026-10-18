package com.jarvis.assistant.autoreply

import android.speech.tts.TextToSpeech
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.util.HopeSpeech

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
        val engine = textToSpeech ?: return
        if (!HopeSpeech.configureLanguage(engine, language, text)) {
            Log.w(TAG, "The shared HOPE announcement voice and English fallback are unavailable in the installed TTS engine.")
            return
        }
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
