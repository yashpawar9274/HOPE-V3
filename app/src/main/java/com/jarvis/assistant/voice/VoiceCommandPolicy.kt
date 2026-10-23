package com.jarvis.assistant.voice

import java.net.URI
import java.util.Locale

enum class VoiceClipboardAction {
    COPY_SELECTED_TEXT,
    COPY_LINK,
    COPY_TEXT,
    PASTE,
    SELECT_ALL
}

data class VoiceClipboardCommand(
    val action: VoiceClipboardAction,
    val value: String? = null
)

data class VoiceClipboardResult(
    val success: Boolean,
    val verified: Boolean,
    val message: String
)

object VoiceClipboardCommandParser {
    private val sensitiveLabel = Regex(
        """\b(?:password|passcode|one[- ]time(?: password)?|otp|verification code|security code|pin|cvv|cvc|card number|credit card|debit card)\b""",
        RegexOption.IGNORE_CASE
    )
    private val digitRun = Regex("""(?:\d[\s-]*){13,19}""")
    private val labeledCode = Regex(
        """\b(?:otp|one[- ]time(?: password)?|verification code|security code|passcode|pin|code)\b\D{0,24}\d{4,8}\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(utterance: String): VoiceClipboardCommand? {
        val text = stripWakePrefix(utterance).trim()
        if (text.isBlank()) return null

        if (Regex("""^(?:select all|select everything)(?: please)?[.!?]?$""", RegexOption.IGNORE_CASE)
                .matches(text)
        ) return VoiceClipboardCommand(VoiceClipboardAction.SELECT_ALL)

        if (Regex(
                """^(?:copy|copy the) (?:selected|highlighted) text(?: please)?[.!?]?$""",
                RegexOption.IGNORE_CASE
            ).matches(text) ||
            Regex("""^copy (?:this|the) text(?: please)?[.!?]?$""", RegexOption.IGNORE_CASE)
                .matches(text)
        ) return VoiceClipboardCommand(VoiceClipboardAction.COPY_SELECTED_TEXT)

        val explicitText = Regex(
            """^copy (?:this )?text(?:\s*:\s*|\s+(?:saying|that is)\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.groupValues?.get(1)?.trim()
        if (!explicitText.isNullOrBlank()) {
            return VoiceClipboardCommand(VoiceClipboardAction.COPY_TEXT, explicitText)
        }

        val linkCommand = Regex(
            """^copy (?:this |the |current )?link(?:\s+(.+))?[.!?]?$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)
        if (linkCommand != null) {
            val explicitUrl = linkCommand.groupValues.getOrNull(1)?.trim().orEmpty()
            return VoiceClipboardCommand(
                VoiceClipboardAction.COPY_LINK,
                explicitUrl.takeIf(String::isNotBlank)
            )
        }

        if (Regex(
                """^paste(?: here| (?:the )?copied (?:text|link))?(?: please)?[.!?]?$""",
                RegexOption.IGNORE_CASE
            ).matches(text)
        ) return VoiceClipboardCommand(VoiceClipboardAction.PASTE)

        return null
    }

    fun isValidHttpUrl(value: String): Boolean {
        if (value.isBlank() || value.any(Char::isWhitespace)) return false
        return try {
            val uri = URI(value)
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun isSensitive(value: String, context: String = ""): Boolean {
        val combined = "$context $value"
        if (sensitiveLabel.containsMatchIn(combined) || labeledCode.containsMatchIn(combined)) {
            return true
        }
        val digits = digitRun.findAll(value).map { it.value.filter(Char::isDigit) }
        if (digits.any { it.length in 13..19 && passesLuhn(it) }) return true
        return value.trim().matches(Regex("""\d{4,8}"""))
    }

    private fun stripWakePrefix(value: String): String =
        value.trim().replaceFirst(
            Regex("""^(?:(?:hey|hello|hi)\s+)?hope\b[\s,.:!-]*""", RegexOption.IGNORE_CASE),
            ""
        )

    private fun passesLuhn(number: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (index in number.indices.reversed()) {
            var digit = number[index].digitToInt()
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum > 0 && sum % 10 == 0
    }
}

object WakeWordConfidence {
    fun isConfirmed(
        words: List<String>,
        confidences: List<Double>,
        minimumConfidence: Float
    ): Boolean {
        if (words.size != 2 || confidences.size != 2) return false
        if (words.map { it.lowercase(Locale.ROOT) } != listOf("hey", "hope")) return false
        val threshold = minimumConfidence.coerceIn(0.50f, 0.95f).toDouble()
        return confidences.all { it.isFinite() && it >= threshold }
    }
}

object StandbyCommandPolicy {
    private val wakePrefix = Regex(
        """^(?:(?:hey|hello|hi)\s+)?hope\b[\s,.:!-]*""",
        RegexOption.IGNORE_CASE
    )
    private val explicitCommands = setOf(
        "activate standby",
        "activate standby mode",
        "enable standby",
        "enable standby mode",
        "enter standby",
        "enter standby mode",
        "go to standby",
        "go to standby mode",
        "go standby",
        "standby",
        "go to background",
        "go to the background",
        "send to background",
        "background me jao",
        "background mein jao",
        "so jao"
    )

    fun isExplicitCommand(utterance: String): Boolean {
        val normalized = utterance.trim()
            .replace(wakePrefix, "")
            .trim()
            .trimEnd('.', '!', '?')
            .lowercase(Locale.ROOT)
        return normalized in explicitCommands
    }
}

object DeviceUnlockCommandPolicy {
    private val wakePrefix = Regex(
        """^(?:(?:hey|hello|hi)\s+)?hope\b[\s,.:!-]*""",
        RegexOption.IGNORE_CASE
    )
    private val explicitCommands = setOf(
        "unlock my phone",
        "unlock the phone",
        "unlock phone",
        "unlock my device",
        "unlock the device",
        "phone unlock karo",
        "mera phone unlock karo"
    )

    fun isExplicitCommand(utterance: String): Boolean {
        val normalized = utterance.trim()
            .replace(wakePrefix, "")
            .trim()
            .trimEnd('.', '!', '?')
            .lowercase(Locale.ROOT)
        return normalized in explicitCommands
    }
}
