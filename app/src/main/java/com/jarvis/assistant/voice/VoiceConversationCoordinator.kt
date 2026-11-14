package com.jarvis.assistant.voice

import java.util.Locale

enum class VoiceConversationPhase {
    IDLE,
    WAKE_WORD_DETECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

enum class VoiceResponsePath {
    GEMINI_AUDIO,
    LOCAL_TTS
}

class VoiceConversationCoordinator {
    private var nextActivationId = 0L
    private var activeActivationId: Long? = null
    private var greetedActivationId: Long? = null

    @Volatile
    var phase: VoiceConversationPhase = VoiceConversationPhase.IDLE
        private set

    @Synchronized
    fun onWakeWordDetected(): Long? {
        if (phase != VoiceConversationPhase.IDLE) return null
        val activationId = ++nextActivationId
        activeActivationId = activationId
        phase = VoiceConversationPhase.WAKE_WORD_DETECTED
        return activationId
    }

    @Synchronized
    fun claimWakeGreeting(activationId: Long): Boolean {
        if (activeActivationId != activationId || greetedActivationId == activationId) return false
        greetedActivationId = activationId
        return true
    }

    @Synchronized
    fun transitionTo(activationId: Long, next: VoiceConversationPhase): Boolean {
        if (activeActivationId != activationId) return false
        return transitionTo(next)
    }

    @Synchronized
    fun transitionTo(next: VoiceConversationPhase): Boolean {
        if (phase == next) return true
        if (next !in allowedTransitions.getValue(phase)) return false
        phase = next
        return true
    }

    @Synchronized
    fun endActivation(activationId: Long): Boolean {
        if (activeActivationId != activationId) return false
        resetForStandby()
        return true
    }

    @Synchronized
    fun resetForStandby() {
        activeActivationId = null
        greetedActivationId = null
        phase = VoiceConversationPhase.IDLE
    }

    companion object {
        private val allowedTransitions = mapOf(
            VoiceConversationPhase.IDLE to setOf(
                VoiceConversationPhase.WAKE_WORD_DETECTED,
                VoiceConversationPhase.LISTENING,
                VoiceConversationPhase.THINKING,
                VoiceConversationPhase.ERROR
            ),
            VoiceConversationPhase.WAKE_WORD_DETECTED to setOf(
                VoiceConversationPhase.IDLE,
                VoiceConversationPhase.LISTENING,
                VoiceConversationPhase.THINKING,
                VoiceConversationPhase.SPEAKING,
                VoiceConversationPhase.ERROR
            ),
            VoiceConversationPhase.LISTENING to setOf(
                VoiceConversationPhase.IDLE,
                VoiceConversationPhase.THINKING,
                VoiceConversationPhase.SPEAKING,
                VoiceConversationPhase.ERROR
            ),
            VoiceConversationPhase.THINKING to setOf(
                VoiceConversationPhase.IDLE,
                VoiceConversationPhase.LISTENING,
                VoiceConversationPhase.SPEAKING,
                VoiceConversationPhase.ERROR
            ),
            VoiceConversationPhase.SPEAKING to setOf(
                VoiceConversationPhase.IDLE,
                VoiceConversationPhase.LISTENING,
                VoiceConversationPhase.THINKING,
                VoiceConversationPhase.ERROR
            ),
            VoiceConversationPhase.ERROR to setOf(
                VoiceConversationPhase.IDLE,
                VoiceConversationPhase.WAKE_WORD_DETECTED,
                VoiceConversationPhase.LISTENING
            )
        )
    }
}

object VoiceConversationPolicy {
    fun systemInstruction(language: String): String {
        val languagePolicy = when (language.trim().lowercase(Locale.ROOT)) {
            "hindi" -> "Use the configured Hindi for speech; if set to Auto, follow the user's latest language."
            "hinglish" -> "Use natural conversational Hinglish; if set to Auto, follow the user's latest language."
            "english" -> "Use the configured English for speech; if set to Auto, follow the user's latest language."
            else -> "Follow the configured spoken language. When it is Auto, match the language and script of the user's latest utterance, including Hindi, Hinglish, or English."
        }
        return """
            [VOICE CONVERSATION POLICY]
            - Be a natural, calm, attentive, capable personal AI assistant: warm without pretending to be human or claiming feelings or experiences.
            - $languagePolicy
            - The exact wake-only input "Hey HOPE" starts a new conversation: give one short, natural greeting and then listen. Do not add another greeting to that same response.
            - If a real request is already present, answer the request directly instead of greeting first.
            - Across later turns in this session, use conversation context and answer directly. Never append a generic greeting or repeat a question already answered.
            - Estimate emotional tone probabilistically from the words and delivery; cues can be ambiguous, so never claim certainty about the user's feelings. Adapt phrasing calmly for happy, excited, sad, angry, frustrated, confused, curious, serious, relaxed, or playful cues.
            - When the user asks what you remember, asks about a previous session, or asks to recall a personal fact that may have been discussed before, use the read-only search_long_term_memory tool if cloud memory is enabled. Never invent a recalled fact; say when no matching memory is found.
            - A brief acknowledgment is appropriate only when the user explicitly interrupts or asks you to listen; then continue with the user's request.
            - Do not repeat prior assistant audio or text after reconnecting. Do not narrate internal reasoning, invent memory, or claim to have heard or done anything unsupported by the conversation or tools.
            - Keep spoken responses concise, composed, and conversational. Ask a clarifying question only when the missing detail is necessary.
        """.trimIndent()
    }

    fun outputPath(isLiveSession: Boolean, hasGeminiAudio: Boolean): VoiceResponsePath = when {
        isLiveSession && hasGeminiAudio -> VoiceResponsePath.GEMINI_AUDIO
        else -> VoiceResponsePath.LOCAL_TTS
    }
}

object VoiceTranscriptAccumulator {
    fun merge(previousText: String, incomingText: String): String {
        val previous = previousText.trimEnd()
        val incoming = incomingText.trim()
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming

        if (previous.equals(incoming, ignoreCase = true) ||
            previous.endsWith(incoming, ignoreCase = true) ||
            previous.startsWith(incoming, ignoreCase = true)
        ) return previous

        if (incoming.startsWith(previous, ignoreCase = true)) return incoming

        val maxOverlap = minOf(previous.length, incoming.length)
        for (overlap in maxOverlap downTo 1) {
            if (previous.regionMatches(
                    previous.length - overlap,
                    incoming,
                    0,
                    overlap,
                    ignoreCase = true
                )
            ) {
                return previous + incoming.substring(overlap)
            }
        }

        val needsSeparator = !previous.last().isWhitespace() &&
            !incoming.first().isWhitespace() &&
            incoming.first().isLetterOrDigit()
        return previous + (if (needsSeparator) " " else "") + incoming
    }
}
