package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryAnswerPolicyTest {
    @Test
    fun unknownFavoriteSongIsNotInvented() {
        assertEquals(
            "I don't know your favorite song yet, Sir.",
            MemoryAnswerPolicy.favoriteSongResponse("")
        )
    }

    @Test
    fun knownFavoriteSongIsReported() {
        assertEquals(
            "Sir, your favorite song is Nadaan Parindey.",
            MemoryAnswerPolicy.favoriteSongResponse("Nadaan Parindey")
        )
    }

    @Test
    fun previouslyStoredButUnconfirmedValueIsNotTreatedAsKnown() {
        assertEquals(null, MemoryAnswerPolicy.confirmedValue("Tadipaar", isConfirmed = false))
        assertEquals("YouTube", MemoryAnswerPolicy.confirmedValue(" YouTube ", isConfirmed = true))
    }
}
