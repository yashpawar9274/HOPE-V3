package com.jarvis.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGeneratorGenderTest {
    private fun prompt(voice: String) = PromptGenerator.generateSystemPrompt(
        personality = "HOPE Pro",
        userName = "YASH",
        language = "Hinglish",
        voiceName = voice
    )

    @Test
    fun femaleVoiceGetsFeminineGrammarAndNoMasculineExamples() {
        val female = prompt("Aoede")
        assertTrue(female.trimStart().startsWith("[FEMININE VOICE MANDATE"))
        assertTrue(female.contains("kar deti hoon"))
        assertFalse(female.contains("kar deta hoon"))
        assertFalse(female.contains("kar raha hoon"))
        assertFalse(female.contains("karta hoon"))
        assertTrue(female.contains("मैं कर रही हूँ"))
    }

    @Test
    fun maleVoiceIsLeftAlone() {
        assertFalse(prompt("Puck").contains("FEMININE VOICE MANDATE"))
    }

    @Test
    fun hopeOnlyAnswersWhenSpokenTo() {
        val text = prompt("Aoede")
        assertTrue(text.contains("ONLY ANSWER WHEN SPOKEN TO"))
        assertTrue(text.contains("stay completely silent"))
        assertFalse(text.contains("NEVER REMAIN SILENT"))
    }

    @Test
    fun femaleVoicesAreRecognised() {
        listOf("Aoede", "Kore", "Leda", "Zephyr").forEach { assertTrue(PromptGenerator.isFemaleVoice(it)) }
        assertFalse(PromptGenerator.isFemaleVoice("Puck"))
        assertFalse(PromptGenerator.isFemaleVoice("Charon"))
    }
}
