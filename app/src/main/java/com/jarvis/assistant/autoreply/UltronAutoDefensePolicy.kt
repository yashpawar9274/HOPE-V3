package com.jarvis.assistant.autoreply

data class AbuseClassification(
    val targetedAbuse: Boolean,
    val confidence: Double,
    val quotedOrReported: Boolean,
    val playfulOrNonTargeted: Boolean
)

data class UltronDefenseSession(
    val conversationHash: String,
    val originalPersonaId: String,
    val startedAt: Long,
    val expiresAt: Long,
    val identityIntroduced: Boolean
)

object UltronAutoDefensePolicy {
    const val MIN_CONFIDENCE = 0.9
    const val MAX_REPLY_LENGTH = 120
    private const val IDENTITY_INTRO = "Main ULTRON hoon."

    private val unsafeReplyPattern = Regex(
        """\b(?:i(?:'ll| will) (?:kill|hurt|beat|find|expose|dox)|kill you|hurt you|beat you|find your address|expose your private|(?:idiot|moron|loser|pathetic|stupid|worthless|useless|dumb|fool|ugly|fat|bewakoof|nikamma))\b""",
        RegexOption.IGNORE_CASE
    )

    fun parseClassification(response: String): AbuseClassification? {
        val json = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!json.startsWith("{") || !json.endsWith("}")) return null

        fun field(name: String, valuePattern: String): String? {
            val matches = Regex(
                """"$name"\s*:\s*($valuePattern)(?=\s*[,}])""",
                RegexOption.IGNORE_CASE
            )
                .findAll(json)
                .toList()
            return matches.singleOrNull()?.groupValues?.get(1)
        }

        val targeted = field("targeted_abuse", "true|false")?.toBooleanStrictOrNull() ?: return null
        val confidence = field("confidence", """(?:0(?:\.\d+)?|1(?:\.0+)?)""")
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..1.0 }
            ?: return null
        val quotedOrReported = field("quoted_or_reported", "true|false")
            ?.toBooleanStrictOrNull()
            ?: return null
        val playfulOrNonTargeted = field("playful_or_non_targeted", "true|false")
            ?.toBooleanStrictOrNull()
            ?: return null

        return AbuseClassification(targeted, confidence, quotedOrReported, playfulOrNonTargeted)
    }

    fun shouldActivate(classification: AbuseClassification?, threshold: Double = MIN_CONFIDENCE): Boolean =
        classification != null &&
            threshold in 0.0..1.0 &&
            classification.targetedAbuse &&
            classification.confidence >= threshold &&
            !classification.quotedOrReported &&
            !classification.playfulOrNonTargeted

    fun firstReplyWithIdentity(reply: String): String {
        val body = reply.trim().takeIf(String::isNotBlank) ?: boundaryFallback()
        val identified = if (Regex("""\bmain\s+ultron\s+hoon\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            body
        } else {
            "$IDENTITY_INTRO $body"
        }
        return identified.take(MAX_REPLY_LENGTH).trimEnd()
    }

    fun isSafeDefenseReply(reply: String): Boolean =
        reply.isNotBlank() && !unsafeReplyPattern.containsMatchIn(reply)

    fun boundaryFallback(): String =
        "$IDENTITY_INTRO Gaali se point strong nahi hota; baat karni ho to tameez se karein."
}
