package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationCoordinatorTest {

    @Test
    fun oneWakeActivationClaimsExactlyOneGreeting() {
        val coordinator = VoiceConversationCoordinator()
        val activation = coordinator.onWakeWordDetected()

        assertTrue(activation != null && coordinator.claimWakeGreeting(activation))
        assertFalse(coordinator.claimWakeGreeting(activation!!))
    }

    @Test
    fun duplicateWakeCallbacksAreIgnoredUntilLifecycleResets() {
        val coordinator = VoiceConversationCoordinator()
        val activation = coordinator.onWakeWordDetected()

        assertNull(coordinator.onWakeWordDetected())
        assertTrue(activation != null && coordinator.claimWakeGreeting(activation))
    }

    @Test
    fun aNewActivationWorksAfterReturningToStandby() {
        val coordinator = VoiceConversationCoordinator()
        val first = coordinator.onWakeWordDetected()!!
        assertTrue(coordinator.claimWakeGreeting(first))

        coordinator.resetForStandby()
        val next = coordinator.onWakeWordDetected()

        assertNotEquals(first, next)
        assertTrue(next != null && coordinator.claimWakeGreeting(next))
    }

    @Test
    fun greetingIsClaimedOnlyForActivationNotForLaterTurnsOrReconnects() {
        val coordinator = VoiceConversationCoordinator()
        val activation = coordinator.onWakeWordDetected()!!
        assertTrue(coordinator.claimWakeGreeting(activation))
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.LISTENING))
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.THINKING))
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.LISTENING))

        assertFalse(coordinator.claimWakeGreeting(activation))
        assertEquals(VoiceConversationPhase.LISTENING, coordinator.phase)
    }

    @Test
    fun cumulativePartialAndFinalTranscriptsDoNotDuplicateText() {
        var transcript = VoiceTranscriptAccumulator.merge("", "turn left")
        transcript = VoiceTranscriptAccumulator.merge(transcript, "turn left at the next street")
        transcript = VoiceTranscriptAccumulator.merge(transcript, "at the next street")

        assertEquals("turn left at the next street", transcript)
    }

    @Test
    fun transcriptChunksMergeOverlappingBoundaries() {
        assertEquals(
            "Please open settings now",
            VoiceTranscriptAccumulator.merge("Please open setting", "settings now")
        )
        assertEquals(
            "Hello, there.",
            VoiceTranscriptAccumulator.merge("Hello", ", there.")
        )
    }

    @Test
    fun interruptionTransitionsFromSpeakingBackToListening() {
        val coordinator = VoiceConversationCoordinator()
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.LISTENING))
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.THINKING))
        assertTrue(coordinator.transitionTo(VoiceConversationPhase.SPEAKING))

        assertTrue(coordinator.transitionTo(VoiceConversationPhase.LISTENING))
        assertEquals(VoiceConversationPhase.LISTENING, coordinator.phase)
    }

    @Test
    fun liveAudioAndOfflineTtsUseMutuallyExclusiveOutputPaths() {
        assertEquals(
            VoiceResponsePath.GEMINI_AUDIO,
            VoiceConversationPolicy.outputPath(isLiveSession = true, hasGeminiAudio = true)
        )
        assertEquals(
            VoiceResponsePath.NONE,
            VoiceConversationPolicy.outputPath(isLiveSession = true, hasGeminiAudio = false)
        )
        assertEquals(
            VoiceResponsePath.LOCAL_TTS,
            VoiceConversationPolicy.outputPath(isLiveSession = false, hasGeminiAudio = false)
        )
    }

    @Test
    fun conversationPolicySupportsEnglishHindiAndHinglish() {
        val english = VoiceConversationPolicy.systemInstruction("English")
        val hindi = VoiceConversationPolicy.systemInstruction("Hindi")
        val hinglish = VoiceConversationPolicy.systemInstruction("Hinglish")
        val automatic = VoiceConversationPolicy.systemInstruction("Auto")

        assertTrue(english.contains("configured English"))
        assertTrue(hindi.contains("configured Hindi"))
        assertTrue(hinglish.contains("natural conversational Hinglish"))
        assertTrue(automatic.contains("match the language and script"))
        assertTrue(automatic.lowercase().contains("never append a generic greeting"))
    }
}
