package com.jarvis.assistant.ai

data class AiResponse(val text: String, val engine: AiEngine)

enum class AiEngine(val displayName: String) {
    GEMINI("Gemini Online")
}

fun interface TextGenerationEngine {
    suspend fun generate(systemInstruction: String, context: String, prompt: String): String
}

class AiRouter(
    private val cloud: TextGenerationEngine
) {
    suspend fun generate(
        systemInstruction: String,
        context: String,
        prompt: String
    ): AiResponse {
        require(prompt.isNotBlank()) { "Prompt must not be blank." }
        return AiResponse(
            cloud.generate(systemInstruction, context, prompt),
            AiEngine.GEMINI
        )
    }
}
