package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalPolicyTest {
    @Test
    fun retrievesRelevantRecentFactsButOmitsSupersededAndLocationRecords() {
        val birthday = memory(
            key = "birthday",
            value = "15 August",
            rawText = "User's birthday is 15 August",
            timestamp = 2L
        )
        val oldBirthday = memory(
            key = "birthday_old",
            value = "12 July",
            rawText = "User's birthday used to be 12 July",
            status = MemoryEntity.STATUS_SUPERSEDED,
            timestamp = 3L
        )
        val location = memory(
            key = "user_location",
            value = "Pune",
            rawText = "User lives in Pune"
        )
        val unrelated = memory(
            key = "favorite_song",
            value = "Nadaan Parindey",
            rawText = "User's favorite song is Nadaan Parindey"
        )

        val result = MemoryRetrievalPolicy.selectRelevant(
            query = "What is my birthday?",
            memories = listOf(unrelated, oldBirthday, location, birthday)
        )

        assertEquals(listOf(birthday), result)
    }

    @Test
    fun ignoresQueriesWithoutUsefulTermsAndHonorsResultLimit() {
        val memories = listOf(
            memory("birthday", "15 August", "User birthday", timestamp = 2L),
            memory("favorite_song", "Song A", "Favorite song", timestamp = 1L)
        )

        assertTrue(MemoryRetrievalPolicy.selectRelevant("what is my?", memories).isEmpty())
        assertEquals(
            1,
            MemoryRetrievalPolicy.selectRelevant("birthday favorite song", memories, limit = 1).size
        )
    }

    private fun memory(
        key: String,
        value: String,
        rawText: String,
        status: String = MemoryEntity.STATUS_USER_CONFIRMED,
        timestamp: Long = 1L
    ) = MemoryEntity(
        category = MemoryEntity.CATEGORY_NOTES,
        key = key,
        value = value,
        rawText = rawText,
        verificationStatus = status,
        timestamp = timestamp
    )
}
