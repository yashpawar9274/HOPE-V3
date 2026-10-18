package com.jarvis.assistant.ai

import kotlinx.coroutines.CancellationException

enum class AiMode {
    ONLINE,
    OFFLINE,
    AUTOMATIC;

    companion object {
        fun fromPreference(value: String): AiMode =
            entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

enum class AiEngine(val displayName: String) {
    GEMINI("Gemini Online"),
    GEMMA("Gemma Offline")
}

enum class GemmaBackend {
    CPU,
    GPU;

    companion object {
        fun fromPreference(value: String): GemmaBackend =
            entries.firstOrNull { it.name == value } ?: CPU
    }
}

data class AiResponse(val text: String, val engine: AiEngine)

fun interface TextGenerationEngine {
    suspend fun generate(systemInstruction: String, context: String, prompt: String): String
}

class AiRouter(
    private val cloud: TextGenerationEngine,
    private val local: TextGenerationEngine,
    private val cloudAvailable: () -> Boolean,
    private val cloudAllowedInAutomatic: () -> Boolean
) {
    suspend fun generate(
        mode: AiMode,
        systemInstruction: String,
        context: String,
        prompt: String
    ): AiResponse {
        require(prompt.isNotBlank()) { "Prompt must not be blank." }
        return when (mode) {
            AiMode.ONLINE -> AiResponse(
                cloud.generate(systemInstruction, context, prompt),
                AiEngine.GEMINI
            )
            AiMode.OFFLINE -> AiResponse(
                local.generate(systemInstruction, context, prompt),
                AiEngine.GEMMA
            )
            AiMode.AUTOMATIC -> generateAutomatically(systemInstruction, context, prompt)
        }
    }

    private suspend fun generateAutomatically(
        systemInstruction: String,
        context: String,
        prompt: String
    ): AiResponse {
        if (cloudAllowedInAutomatic() && cloudAvailable()) {
            try {
                return AiResponse(
                    cloud.generate(systemInstruction, context, prompt),
                    AiEngine.GEMINI
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cloudError: Exception) {
                try {
                    return AiResponse(
                        local.generate(systemInstruction, context, prompt),
                        AiEngine.GEMMA
                    )
                } catch (localError: Exception) {
                    localError.addSuppressed(cloudError)
                    throw localError
                }
            }
        }
        return AiResponse(local.generate(systemInstruction, context, prompt), AiEngine.GEMMA)
    }
}
