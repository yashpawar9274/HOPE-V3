package com.jarvis.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGeneratorMemoryTest {
    @Test
    fun unknownFavoriteSongIsNotInventedInVoicePrompt() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "HOPE",
            userName = "YASH"
        )

        assertTrue(prompt.contains("The user's favorite song is not known"))
        assertTrue(prompt.contains("ask which song they mean"))
        assertFalse(prompt.contains("Barsaat"))
    }

    @Test
    fun confirmedFavoriteSongIsUsedWithoutSubstitution() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "HOPE",
            userName = "YASH",
            favoriteSong = "Nadaan Parindey"
        )

        assertTrue(prompt.contains("The user's confirmed favorite song is: \"Nadaan Parindey\""))
        assertFalse(prompt.contains("Barsaat"))
    }

    @Test
    fun selectedPersonaControlsVoiceIdentityAndBehavior() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "FRIDAY",
            userName = "YASH"
        )

        assertTrue(prompt.contains("Identity: FRIDAY"))
        assertTrue(prompt.contains("You are FRIDAY, HOPE's friendly conversational AI persona"))
        assertTrue(prompt.contains("Notice emotional cues"))
        assertTrue(prompt.contains("Your active identity is FRIDAY"))
        assertFalse(prompt.contains("You are HOPE, the user's personal AI assistant"))
        assertFalse(prompt.contains("1. You are HOPE."))
    }
}
