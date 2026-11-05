package com.jarvis.assistant.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Entry point Android uses for the "assist" gesture (power-button long press, home long press,
 * "Hey Google"-style launch). Having an ASSIST activity is what makes HOPE eligible for
 * Settings > Default digital assistant app. It opens HOPE and starts the voice session.
 */
class HopeAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION, true)
            }
        )
        finish()
    }
}
