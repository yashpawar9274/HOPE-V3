package com.jarvis.assistant.telephony

import android.content.Context

object IncomingCallPreferences {
    private const val PREFS = "hope_incoming_call"
    private const val KEY_ANNOUNCEMENTS = "announcements_enabled"
    private const val KEY_VOICE_CONFIRMATION = "voice_confirmation_enabled"
    private const val KEY_LANGUAGE = "language"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAnnouncementEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ANNOUNCEMENTS, false)

    fun setAnnouncementEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_ANNOUNCEMENTS, enabled).commit()) {
            "Could not save incoming-call announcement setting."
        }
    }

    fun isVoiceConfirmationEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VOICE_CONFIRMATION, false)

    fun setVoiceConfirmationEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_VOICE_CONFIRMATION, enabled).commit()) {
            "Could not save incoming-call voice confirmation setting."
        }
    }

    fun language(context: Context): String =
        preferences(context).getString(KEY_LANGUAGE, "Auto") ?: "Auto"

    fun setLanguage(context: Context, language: String) {
        require(language in setOf("Auto", "English", "Hindi")) { "Unsupported incoming-call language." }
        check(preferences(context).edit().putString(KEY_LANGUAGE, language).commit()) {
            "Could not save incoming-call language."
        }
    }
}
