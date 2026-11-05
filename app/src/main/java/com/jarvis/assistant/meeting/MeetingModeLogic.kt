package com.jarvis.assistant.meeting

/**
 * Pure (Android-free) rules for HOPE Meeting Mode.
 *
 * While Meeting Mode is on, HOPE declines incoming SIM calls, texts the caller from the owner's own
 * SIM and keeps chatting with them over SMS/WhatsApp (via Smart Auto-Reply). A second call from the
 * same number within [REPEAT_WINDOW_MS] and starred contacts can still ring through.
 *
 * Android does not let an app hear or speak into a cellular call, so HOPE never tries to talk on the
 * call itself - see README_CHANGES.md.
 */
object MeetingModeLogic {
    const val REPEAT_WINDOW_MS = 5 * 60_000L
    const val MAX_CUSTOM_PROMPT_CHARS = 1200
    const val MAX_SMS_CHARS = 300

    enum class Situation(val label: String, val phrase: String, val hinglish: String) {
        MEETING("Meeting", "in a meeting", "meeting mein"),
        SLEEPING("Sleeping", "sleeping", "so rahe hain"),
        BUSY("Busy", "busy right now", "abhi busy hain");

        companion object {
            fun fromName(name: String?): Situation =
                values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: MEETING
        }
    }

    enum class Decision { NOT_ACTIVE, SCREEN, RING_THROUGH_REPEATED, RING_THROUGH_STARRED }

    fun isActive(enabled: Boolean, endAtMillis: Long, now: Long): Boolean =
        enabled && (endAtMillis <= 0L || now < endAtMillis)

    fun decide(
        active: Boolean,
        isStarred: Boolean,
        allowStarred: Boolean,
        allowRepeated: Boolean,
        earlierCallsInWindow: Int
    ): Decision = when {
        !active -> Decision.NOT_ACTIVE
        allowStarred && isStarred -> Decision.RING_THROUGH_STARRED
        allowRepeated && earlierCallsInWindow >= 1 -> Decision.RING_THROUGH_REPEATED
        else -> Decision.SCREEN
    }

    /** Last 10 digits, so "+91 98765 43210", "09876543210" and "9876543210" are the same caller. */
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun ownerLabel(ownerName: String): String =
        ownerName.trim().takeIf { it.isNotBlank() && it.length <= 40 } ?: "the owner"

    fun cleanCustomPrompt(text: String): String =
        text.replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "").trim().take(MAX_CUSTOM_PROMPT_CHARS)

    /** One sentence describing the owner's situation (used inside prompts). */
    fun situationContext(
        ownerName: String,
        situation: Situation,
        untilText: String,
        allowRepeated: Boolean
    ): String {
        val owner = ownerLabel(ownerName)
        return buildString {
            append("$owner is ${situation.phrase}")
            if (untilText.isNotBlank()) append(" until $untilText")
            append(" and cannot talk or take calls right now. ")
            append("Find out who the sender is and what they need, take a short message, and say $owner will get back when free. ")
            if (allowRepeated) append("If it is urgent, tell them to call again within 5 minutes and the phone will ring. ")
            append("Do not reveal more about where $owner is or what they are doing.")
        }
    }

    fun ownerInstructionsBlock(customPrompt: String): String {
        val clean = cleanCustomPrompt(customPrompt)
        return if (clean.isEmpty()) "" else
            "The owner's own instructions about how to talk (follow them unless they conflict with the rules above): $clean"
    }

    /** System prompt for the one SMS HOPE sends right after declining a call. */
    fun buildCallerSmsPrompt(
        ownerName: String,
        situation: Situation,
        untilText: String,
        customPrompt: String,
        callerName: String,
        language: String,
        allowRepeated: Boolean
    ): String {
        val owner = ownerLabel(ownerName)
        val languageRule = when (language) {
            "English" -> "Write in English."
            "Hindi" -> "Write in Hindi (Devanagari script)."
            "Hinglish" -> "Write in Hinglish (Hindi in Roman letters)."
            else -> "Write in Hinglish (Hindi in Roman letters) unless the owner's instructions say otherwise."
        }
        return buildString {
            append("You are HOPE, a woman and the personal AI assistant of $owner. ")
            append("${situationContext(ownerName, situation, untilText, false)} ")
            append("$owner could not take a call from ${callerName.ifBlank { "this number" }}, so you are sending them ONE short SMS from $owner's phone.\n")
            append("Rules:\n")
            append("1. Say you are $ASSISTANT_NAME, $owner's assistant, and that $owner is ${situation.phrase} so could not pick up.\n")
            append("2. Ask who is calling and what it is about, and tell them they can reply here and you will pass the message on.\n")
            if (allowRepeated) append("3. If it is urgent they can call again within 5 minutes and it will ring.\n")
            append("4. You are female: use feminine forms about yourself in Hindi/Hinglish (\"kar rahi hoon\", \"bata dungi\").\n")
            append("5. $languageRule\n")
            append("6. Maximum 220 characters, plain text, no markdown, no links. Do not promise anything for $owner and share no private details.\n")
            val owners = ownerInstructionsBlock(customPrompt)
            if (owners.isNotEmpty()) append("7. $owners\n")
            append("Return ONLY the SMS text.")
        }
    }

    /** Used only if the AI service is not reachable at the moment the call is declined. */
    fun fallbackCallerSms(ownerName: String, situation: Situation): String {
        val owner = ownerLabel(ownerName)
        return "Namaste, main $ASSISTANT_NAME hoon, $owner ki AI assistant. $owner abhi ${situation.hinglish}, isliye aapka call nahi utha paye. " +
            "Aap yahin message karke bata dijiye kya kaam hai, main unhe bata dungi."
    }

    const val ASSISTANT_NAME = "HOPE"

    /** Counts earlier calls per number inside a time window. Thread-safe. */
    class RecentCallTracker {
        private val calls = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun countWithin(key: String, windowMs: Long, now: Long): Int {
            val q = calls[key] ?: return 0
            while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
            return q.size
        }

        @Synchronized
        fun record(key: String, now: Long) {
            val q = calls.getOrPut(key) { ArrayDeque() }
            q.addLast(now)
            if (calls.size > 300) calls.entries.removeAll { it.value.isEmpty() || now - it.value.last() > 60 * 60_000L }
        }
    }
}
