package com.jarvis.assistant.voice

import android.content.Intent
import android.app.KeyguardManager
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.jarvis.assistant.ui.home.MainActivity

class HopeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession =
        HopeVoiceInteractionSession(this)

    companion object {
        const val EXTRA_START_ASSISTANT_VOICE_SESSION =
            "com.jarvis.assistant.voice.START_ASSISTANT_VOICE_SESSION"
    }
}

private class HopeVoiceInteractionSession(
    context: android.content.Context
) : VoiceInteractionSession(context) {
    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val keyguard = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager
        val target = if (keyguard.isKeyguardLocked) {
            LockScreenActivity::class.java
        } else {
            MainActivity::class.java
        }
        val launchIntent = Intent(context, target).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(
                HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION,
                true
            )
        }
        startVoiceActivity(launchIntent)
        hide()
    }
}
