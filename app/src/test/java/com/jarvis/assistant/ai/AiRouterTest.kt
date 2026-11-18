package com.jarvis.assistant.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiRouterTest {
    @Test
    fun routesGenerationToGemini() = runBlocking {
        val cloud = RecordingEngine("online response")
        val router = AiRouter(cloud)

        val result = router.generate("system", "context", "prompt")

        assertEquals("online response", result.text)
        assertEquals(AiEngine.GEMINI, result.engine)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun propagatesCloudFailureInsteadOfFallingBackToOfflineInference() {
        val router = AiRouter(RecordingEngine("unused", IllegalStateException("network unavailable")))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { router.generate("system", "", "prompt") }
        }

        assertEquals("network unavailable", error.message)
    }

    @Test
    fun propagatesCancellation() {
        val router = AiRouter(RecordingEngine("unused", CancellationException("cancelled")))

        assertThrows(CancellationException::class.java) {
            runBlocking { router.generate("system", "", "prompt") }
        }
    }

    @Test
    fun rejectsBlankPromptsBeforeCallingGemini() {
        val cloud = RecordingEngine("unused")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { AiRouter(cloud).generate("system", "", "  ") }
        }
        assertEquals(0, cloud.calls)
    }

    private class RecordingEngine(
        private val response: String,
        private val failure: Exception? = null
    ) : TextGenerationEngine {
        var calls = 0
            private set

        override suspend fun generate(systemInstruction: String, context: String, prompt: String): String {
            calls++
            failure?.let { throw it }
            return response
        }
    }
}
