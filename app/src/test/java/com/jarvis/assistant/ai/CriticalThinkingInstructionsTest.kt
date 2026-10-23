package com.jarvis.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jarvis.assistant.util.PromptGenerator

class CriticalThinkingInstructionsTest {
    @Test
    fun voiceAndTextShareCoreGuidanceWithChannelSpecificStyles() {
        val voice = CriticalThinkingInstructions.forVoice(enabled = true)
        val text = CriticalThinkingInstructions.forText("Custom HOPE instruction", enabled = true)

        assertTrue(voice.contains("[HOPE CRITICAL THINKING & HONESTY]"))
        assertTrue(text.contains("[HOPE CRITICAL THINKING & HONESTY]"))
        assertTrue(voice.contains("one or two spoken sentences"))
        assertTrue(text.contains("Give enough detail for the question"))
        assertTrue(text.startsWith("Custom HOPE instruction"))
    }

    @Test
    fun disabledGuidanceLeavesExistingInstructionsUnchanged() {
        val baseInstruction = "Custom HOPE instruction"

        assertTrue(CriticalThinkingInstructions.forVoice(enabled = false).isEmpty())
        assertTrue(
            CriticalThinkingInstructions.forText(baseInstruction, enabled = false) ==
                baseInstruction
        )
        assertFalse(
            CriticalThinkingInstructions.forText(baseInstruction, enabled = false)
                .contains("[HOPE CRITICAL THINKING & HONESTY]")
        )
    }

    @Test
    fun voicePromptUsesTheSharedGuidanceOnlyWhenEnabled() {
        val enabledPrompt = PromptGenerator.generateSystemPrompt(
            personality = "HOPE",
            userName = "",
            isCriticalThinkingEnabled = true
        )
        val disabledPrompt = PromptGenerator.generateSystemPrompt(
            personality = "HOPE",
            userName = "",
            isCriticalThinkingEnabled = false
        )

        assertTrue(enabledPrompt.contains("[HOPE CRITICAL THINKING & HONESTY]"))
        assertTrue(enabledPrompt.contains("one or two spoken sentences"))
        assertFalse(disabledPrompt.contains("[HOPE CRITICAL THINKING & HONESTY]"))
    }
}
