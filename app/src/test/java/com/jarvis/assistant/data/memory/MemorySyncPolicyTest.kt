package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySyncPolicyTest {
    @Test
    fun aTurnUsesTheSameOwnerScopedOutboxKeyOnEveryEnqueue() {
        val first = MemorySyncPolicy.turnJobId("account-a", "turn-1")

        assertEquals(first, MemorySyncPolicy.turnJobId("account-a", "turn-1"))
        assertFalse(first == MemorySyncPolicy.turnJobId("account-b", "turn-1"))
    }

    @Test
    fun retryableResponsesAreDistinguishedFromPermanentClientErrors() {
        assertTrue(MemorySyncPolicy.isRetryableHttpCode(null))
        assertTrue(MemorySyncPolicy.isRetryableHttpCode(429))
        assertTrue(MemorySyncPolicy.isRetryableHttpCode(503))
        assertFalse(MemorySyncPolicy.isRetryableHttpCode(400))
        assertFalse(MemorySyncPolicy.isRetryableHttpCode(404))
    }

    @Test
    fun disablingCloudMemoryBlocksUploadsButAllowsAnExplicitClear() {
        assertFalse(
            MemorySyncPolicy.shouldProcess(MemorySyncJob.OPERATION_REMEMBER_NOTE, cloudEnabled = false)
        )
        assertFalse(
            MemorySyncPolicy.shouldProcess(MemorySyncJob.OPERATION_SAVE_TURN, cloudEnabled = false)
        )
        assertTrue(
            MemorySyncPolicy.shouldProcess(MemorySyncJob.OPERATION_CLEAR_ALL, cloudEnabled = false)
        )
    }

    @Test
    fun deletingByKeywordEscapesSqlLikeWildcards() {
        assertEquals(
            "100\\%\\_\\\\x",
            MemoryQueryPolicy.escapeLikePattern("100%_\\x")
        )
    }
}
