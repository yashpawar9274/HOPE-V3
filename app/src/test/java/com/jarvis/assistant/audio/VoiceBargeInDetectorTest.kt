package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBargeInDetectorTest {

    @Test
    fun speakerBargeInWorksWithoutEchoCancellationWithStricterDetection() {
        val detector = VoiceBargeInDetector()

        repeat(10) {
            assertFalse(
                detector.observe(
                    amplitude = 0.1f,
                    ambientNoiseFloor = 0.03f,
                    headsetConnected = false,
                    echoCancellationEnabled = false
                )
            )
        }

        repeat(17) {
            assertFalse(
                detector.observe(
                    amplitude = 0.4f,
                    ambientNoiseFloor = 0.03f,
                    headsetConnected = false,
                    echoCancellationEnabled = false
                )
            )
        }
        assertTrue(
            detector.observe(
                amplitude = 0.4f,
                ambientNoiseFloor = 0.03f,
                headsetConnected = false,
                echoCancellationEnabled = false
            )
        )
    }

    @Test
    fun quietFrameResetsSpeakerBargeInCandidate() {
        val detector = VoiceBargeInDetector()
        repeat(7) {
            assertFalse(detector.observe(0.2f, 0.03f, false, true))
        }

        assertFalse(detector.observe(0.01f, 0.03f, false, true))
        repeat(7) {
            assertFalse(detector.observe(0.2f, 0.03f, false, true))
        }
        assertTrue(detector.observe(0.2f, 0.03f, false, true))
    }

    @Test
    fun headsetBargeInRetainsExistingSensitivityWithoutEchoCancellation() {
        val detector = VoiceBargeInDetector()
        repeat(3) {
            assertFalse(detector.observe(0.3f, 0.03f, true, false))
        }
        assertTrue(detector.observe(0.3f, 0.03f, true, false))
    }
}
