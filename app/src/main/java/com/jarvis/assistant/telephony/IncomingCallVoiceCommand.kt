package com.jarvis.assistant.telephony

import java.util.Locale

enum class IncomingCallVoiceCommand {
    ANSWER,
    REJECT
}

object IncomingCallVoiceCommandParser {
    private val rejectPhrases = listOf(
        "reject", "decline", "hang up", "cut the call", "cut call",
        "kaat do", "kat do", "काट दो", "कॉल काट"
    )
    private val answerPhrases = listOf(
        "answer", "accept", "pick up", "receive", "take the call",
        "uthao", "utha lo", "call uthao", "उठाओ", "कॉल उठाओ"
    )
    private val negatedAnswer = Regex(
        """\b(?:do not|don t|dont|never|not)\s+(?:answer|accept|pick up|receive)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(transcript: String): IncomingCallVoiceCommand? {
        val normalized = transcript.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{M}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isBlank()) return null
        if (rejectPhrases.any(normalized::contains)) return IncomingCallVoiceCommand.REJECT
        if (negatedAnswer.containsMatchIn(normalized) ||
            normalized.contains("मत उठाओ") ||
            normalized.contains("नहीं उठाओ")
        ) return null
        if (answerPhrases.any(normalized::contains)) return IncomingCallVoiceCommand.ANSWER
        return null
    }
}
