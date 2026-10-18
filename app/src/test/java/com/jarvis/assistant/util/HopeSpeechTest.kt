package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HopeSpeechTest {
    @Test
    fun configuredAnnouncementLanguageSelectsSharedLocale() {
        assertEquals(Locale("hi", "IN"), HopeSpeech.preferredLocale("Hindi", "Caller"))
        assertEquals(Locale("hi", "IN"), HopeSpeech.preferredLocale("Hinglish", "Caller"))
        assertEquals(Locale.US, HopeSpeech.preferredLocale("English", "नमस्ते"))
    }

    @Test
    fun automaticLanguageUsesAnnouncementTextAndHindiDeviceLocale() {
        val currentLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals(Locale("hi", "IN"), HopeSpeech.preferredLocale("Auto", "नमस्ते"))
            assertEquals(Locale.US, HopeSpeech.preferredLocale("Auto", "Hello caller"))

            Locale.setDefault(Locale("hi", "IN"))
            assertEquals(Locale("hi", "IN"), HopeSpeech.preferredLocale("Auto", "Hello caller"))
        } finally {
            Locale.setDefault(currentLocale)
        }
    }
}
