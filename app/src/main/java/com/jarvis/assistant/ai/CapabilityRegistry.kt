package com.jarvis.assistant.ai

enum class CapabilityAvailability {
    INTEGRATED,
    REQUIRES_RUNTIME_CHECK,
    UNAVAILABLE
}

data class CapabilityDescriptor(
    val id: String,
    val availability: CapabilityAvailability,
    val requirements: String,
    val limitation: String
)

object CapabilityRegistry {
    val capabilities = listOf(
        CapabilityDescriptor(
            "gemini_live_conversation",
            CapabilityAvailability.REQUIRES_RUNTIME_CHECK,
            "Gemini API key, network, microphone permission, and accepted Live model/session setup",
            "A configured voice name does not guarantee a session will connect."
        ),
        CapabilityDescriptor(
            "text_generation",
            CapabilityAvailability.REQUIRES_RUNTIME_CHECK,
            "An available configured AI engine; online mode also requires a valid key and network",
            "Automatic mode may use the local model when online generation is unavailable."
        ),
        CapabilityDescriptor(
            "voice_input_and_output",
            CapabilityAvailability.REQUIRES_RUNTIME_CHECK,
            "Android microphone permission and a working Gemini Live audio session",
            "Gemini audio is produced by the connected Live session; there is no separate Gemini voice-preview flow."
        ),
        CapabilityDescriptor(
            "android_screen_automation",
            CapabilityAvailability.REQUIRES_RUNTIME_CHECK,
            "Enabled Android Accessibility service and any required user-authorized screen access",
            "Actions must use the existing task execution and verification path."
        ),
        CapabilityDescriptor(
            "conversation_memory",
            CapabilityAvailability.INTEGRATED,
            "Saved app conversation or memory context",
            "Only available saved context can be used; it may be incomplete or outdated."
        ),
        CapabilityDescriptor(
            "live_web_research",
            CapabilityAvailability.UNAVAILABLE,
            "A separately integrated web-search capability",
            "The current app does not provide live web search."
        ),
        CapabilityDescriptor(
            "calendar_service",
            CapabilityAvailability.UNAVAILABLE,
            "A separately integrated calendar provider",
            "The current app does not provide calendar access."
        ),
        CapabilityDescriptor(
            "general_file_analysis",
            CapabilityAvailability.UNAVAILABLE,
            "A separately integrated user-file processing flow",
            "Model-file import is not general document analysis."
        )
    )

    fun promptGuidance(): String = """
[CAPABILITY HONESTY]
${capabilities.joinToString("\n") {
        "- ${it.id}: ${it.availability}. Requirements: ${it.requirements}. ${it.limitation}"
    }}
- Check runtime requirements before claiming availability. A persona profile does not add tools, permissions, or model capabilities.
""".trimIndent()
}
