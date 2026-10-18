package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HopeSpeechTest {
    @Test
    fun configuredAnnouncementLanguageSelectsSharedLocale() {
        assertEquals(Locale("hi", "IN"), HopeSpeechLocale.preferredLocale("Hindi", "Caller"))
        assertEquals(Locale("hi", "IN"), HopeSpeechLocale.preferredLocale("Hinglish", "Caller"))
        assertEquals(Locale.US, HopeSpeechLocale.preferredLocale("English", "नमस्ते"))
    }

    @Test
    fun automaticLanguageUsesAnnouncementTextAndHindiDeviceLocale() {
        val currentLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals(Locale("hi", "IN"), HopeSpeechLocale.preferredLocale("Auto", "नमस्ते"))
            assertEquals(Locale.US, HopeSpeechLocale.preferredLocale("Auto", "Hello caller"))

            Locale.setDefault(Locale("hi", "IN"))
            assertEquals(Locale("hi", "IN"), HopeSpeechLocale.preferredLocale("Auto", "Hello caller"))
        } finally {
            Locale.setDefault(currentLocale)
        }
    }
}
