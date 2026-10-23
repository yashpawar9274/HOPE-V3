package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePersonaRegistryTest {
    @Test
    fun everyLiveSupportedVoiceHasOneDistinctPersona() {
        assertEquals(GeminiConstants.SUPPORTED_VOICES.toSet(), VoicePersonaRegistry.profiles.map { it.voiceName }.toSet())
        assertEquals(VoicePersonaRegistry.profiles.size, VoicePersonaRegistry.profiles.map { it.personaName }.toSet().size)
    }

    @Test
    fun automaticExpertiseRoutingDoesNotChangeTheSelectedAudioVoice() {
        val recommended = VoicePersonaRegistry.selectForTask(
            selectedVoice = "Aoede",
            task = "Help me debug a Kotlin crash",
            automaticSwitchingEnabled = true
        )
        val fixed = VoicePersonaRegistry.selectForTask(
            selectedVoice = "Aoede",
            task = "Help me debug a Kotlin crash",
            automaticSwitchingEnabled = false
        )

        assertEquals("Fenrir", recommended.voiceName)
        assertEquals("Aoede", fixed.voiceName)
    }

    @Test
    fun capabilityGuidanceDoesNotClaimUnavailableResearchTools() {
        assertTrue(CapabilityRegistry.promptGuidance().contains("live_web_research: UNAVAILABLE"))
        assertTrue(CapabilityRegistry.promptGuidance().contains("A persona profile does not add tools"))
    }
}
