package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerProfileMatcherTest {
    @Test
    fun selectsBestProfileWhenItPassesThresholdAndIsNotAmbiguous() {
        val target = vector(0)
        val profiles = listOf(
            profile("first", vector(0)),
            profile("second", vector(1))
        )

        val match = SpeakerProfileMatcher.match(target, profiles, 0.70f)

        assertEquals("first", match.profile?.id)
        assertFalse(match.isAmbiguous)
    }

    @Test
    fun returnsUnknownWhenBestDistanceExceedsThreshold() {
        val match = SpeakerProfileMatcher.match(
            vector(0),
            listOf(profile("different", vector(1))),
            0.70f
        )

        assertNull(match.profile)
    }

    @Test
    fun returnsUnknownForProfilesThatAreTooCloseToDistinguish() {
        val target = vector(0)
        val profiles = listOf(
            profile("first", target),
            profile("second", target.copyOf())
        )

        val match = SpeakerProfileMatcher.match(target, profiles, 0.70f)

        assertNull(match.profile)
        assertTrue(match.isAmbiguous)
    }

    @Test
    fun returnsUnknownForInvalidEmbedding() {
        val match = SpeakerProfileMatcher.match(
            floatArrayOf(Float.NaN, 0f),
            listOf(profile("first", vector(0))),
            0.70f
        )

        assertNull(match.profile)
    }

    private fun profile(id: String, embedding: FloatArray) = SpeakerProfile(
        id = id,
        preferredName = id,
        preferredAddress = id,
        language = "English",
        embeddings = listOf(embedding)
    )

    private fun vector(index: Int): FloatArray = FloatArray(128) { if (it == index) 1f else 0f }
}
