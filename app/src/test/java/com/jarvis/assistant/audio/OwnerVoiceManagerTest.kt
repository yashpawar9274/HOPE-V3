package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceManagerTest {

    @Test
    fun missingOrIncompatibleProfileDoesNotProduceAMatch() {
        assertNull(OwnerVoiceManager.bestProfileDistance(floatArrayOf(1f, 0f), null, emptyList()))
        assertNull(OwnerVoiceManager.bestProfileDistance(floatArrayOf(1f, 0f), floatArrayOf(1f), emptyList()))
    }

    @Test
    fun invalidVectorsAreRejected() {
        assertFalse(OwnerVoiceManager.isValidVector(floatArrayOf(0f, 0f)))
        assertFalse(OwnerVoiceManager.isValidVector(floatArrayOf(Float.NaN, 1f)))
        assertFalse(OwnerVoiceManager.isValidVector(floatArrayOf(Float.POSITIVE_INFINITY, 1f)))
        assertEquals(2f, OwnerVoiceManager.calculateCosineDistance(floatArrayOf(1f, 0f), floatArrayOf(Float.NaN, 1f)))
    }

    @Test
    fun averagingOnlyUsesValidVectorsWithMatchingDimensions() {
        val average = OwnerVoiceManager.averageVectors(
            listOf(
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(100f, 100f, 100f),
                floatArrayOf(Float.NaN, 1f)
            )
        )

        assertNotNull(average)
        assertEquals(0f, OwnerVoiceManager.calculateCosineDistance(average!!, floatArrayOf(1f, 1f)), 0.0001f)
    }

    @Test
    fun thresholdBoundaryIsInclusiveButNeverLoosened() {
        assertTrue(OwnerVoiceManager.matchesThreshold(0.70f, 0.70f))
        assertFalse(OwnerVoiceManager.matchesThreshold(0.701f, 0.70f))
        assertFalse(OwnerVoiceManager.matchesThreshold(Float.NaN, 0.70f))
    }
}
