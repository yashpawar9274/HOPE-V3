package com.jarvis.assistant.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    @Test
    fun offlineModeNeverCallsCloud() = runBlocking {
        val cloud = RecordingEngine("cloud")
        val local = RecordingEngine("local")

        val result = router(cloud, local, cloudAllowed = true, online = true)
            .generate(AiMode.OFFLINE, "system", "context", "prompt")

        assertEquals(AiEngine.GEMMA, result.engine)
        assertEquals(0, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun automaticModeUsesLocalWhenPrivacyDisablesCloud() = runBlocking {
        val cloud = RecordingEngine("cloud")
        val local = RecordingEngine("local")

        val result = router(cloud, local, cloudAllowed = false, online = true)
            .generate(AiMode.AUTOMATIC, "system", "", "prompt")

        assertEquals(AiEngine.GEMMA, result.engine)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun automaticModeUsesCloudWhenAllowedAndAvailable() = runBlocking {
        val cloud = RecordingEngine("cloud")
        val local = RecordingEngine("local")

        val result = router(cloud, local, cloudAllowed = true, online = true)
            .generate(AiMode.AUTOMATIC, "system", "", "prompt")

        assertEquals(AiEngine.GEMINI, result.engine)
        assertEquals(1, cloud.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun automaticModeFallsBackToLocalWhenCloudFails() = runBlocking {
        val cloud = RecordingEngine("cloud", IllegalStateException("cloud unavailable"))
        val local = RecordingEngine("local")

        val result = router(cloud, local, cloudAllowed = true, online = true)
            .generate(AiMode.AUTOMATIC, "system", "", "prompt")

        assertEquals(AiEngine.GEMMA, result.engine)
        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun cloudCancellationDoesNotTriggerFallback() {
        val cloud = RecordingEngine("cloud", CancellationException("cancelled"))
        val local = RecordingEngine("local")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                router(cloud, local, cloudAllowed = true, online = true)
                    .generate(AiMode.AUTOMATIC, "system", "", "prompt")
            }
        }
        assertEquals(0, local.calls)
    }

    @Test
    fun onlineModeDoesNotFallBackToLocal() {
        val cloud = RecordingEngine("cloud", IllegalStateException("cloud unavailable"))
        val local = RecordingEngine("local")

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                router(cloud, local, cloudAllowed = false, online = false)
                    .generate(AiMode.ONLINE, "system", "", "prompt")
            }
        }
        assertEquals(0, local.calls)
    }

    @Test
    fun downloadProgressIsBoundedAndExact() {
        assertEquals(0, GemmaModelState.Downloading(0, 100).progressPermille)
        assertEquals(500, GemmaModelState.Downloading(50, 100).progressPermille)
        assertEquals(1000, GemmaModelState.Downloading(120, 100).progressPermille)
        assertEquals(0, GemmaModelState.Downloading(20, 0).progressPermille)
    }

    @Test
    fun pinnedGemmaArtifactHasExpectedMetadata() {
        assertEquals(584_417_280L, GemmaModelManager.MODEL_BYTES)
        assertEquals(
            "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            GemmaModelManager.MODEL_FILE
        )
        assertTrue(GemmaModelManager.MODEL_REVISION.matches(Regex("[0-9a-f]{40}")))
    }

    private fun router(
        cloud: RecordingEngine,
        local: RecordingEngine,
        cloudAllowed: Boolean,
        online: Boolean
    ) = AiRouter(cloud, local, { online }, { cloudAllowed })

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
