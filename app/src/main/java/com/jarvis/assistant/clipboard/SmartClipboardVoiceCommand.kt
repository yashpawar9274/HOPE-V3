package com.jarvis.assistant.clipboard

import java.util.Locale

enum class SmartClipboardRequest {
    EXPLAIN,
    SUGGEST_REPLY,
    TRANSLATE,
    EXPLAIN_CODE,
    EXPLAIN_IMAGE
}

object SmartClipboardVoiceCommandParser {
    private val wakePrefix = Regex(
        """^(?:(?:hey|hello|hi)\s+)?hope\b[\s,.:!-]*""",
        RegexOption.IGNORE_CASE
    )

    private val commands = listOf(
        SmartClipboardRequest.EXPLAIN_IMAGE to listOf(
            "copied image explain karo",
            "explain my copied image",
            "explain the copied image",
            "what is in my copied image"
        ),
        SmartClipboardRequest.EXPLAIN_CODE to listOf(
            "explain this copied code",
            "explain my copied code",
            "explain the copied code",
            "copied code explain karo"
        ),
        SmartClipboardRequest.SUGGEST_REPLY to listOf(
            "iska reply kya dena chahiye",
            "what should i reply",
            "what should i reply to this",
            "suggest a reply to what i copied",
            "suggest reply for copied text"
        ),
        SmartClipboardRequest.TRANSLATE to listOf(
            "translate my copied content",
            "translate what i copied",
            "translate the copied text",
            "translate my copied text",
            "mere copied text ka translation karo"
        ),
        SmartClipboardRequest.EXPLAIN to listOf(
            "clipboard read karo",
            "read my clipboard",
            "read clipboard",
            "maine jo copy kiya hai uska matlab batao",
            "maine jo copy kiya uska matlab batao",
            "what did i copy",
            "explain my copied text",
            "explain what i copied",
            "explain copied content",
            "copied content explain karo"
        )
    )

    fun parse(utterance: String): SmartClipboardRequest? {
        val normalized = normalize(utterance)
        if (normalized.isBlank()) return null
        return commands.firstNotNullOfOrNull { (request, phrases) ->
            request.takeIf { normalized in phrases }
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .replace(wakePrefix, "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
