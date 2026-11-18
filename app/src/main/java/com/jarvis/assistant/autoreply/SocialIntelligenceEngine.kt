package com.jarvis.assistant.autoreply

import com.jarvis.assistant.ai.PersonaEngine
import java.util.Locale

data class SocialMessage(
    val sender: String,
    val text: String
)

data class SocialAnalysis(
    val intent: Intent,
    val tone: Tone,
    val shouldRespond: Boolean,
    val requiresOwnerReview: Boolean,
    val stopAfterResponse: Boolean,
    val sensitive: Boolean,
    val summary: String,
    val missingInformation: List<String>,
    val safeHoldingReply: String? = null
) {
    enum class Intent {
        IDENTITY_QUESTION,
        STOP_BOUNDARY,
        CAREER_DECISION,
        MEETING_REQUEST,
        CONVERSATION_END,
        SENDER_BUSY,
        UNNECESSARY_MESSAGE,
        REPEATED_ANSWERED_QUESTION,
        NORMAL
    }

    enum class Tone {
        NEUTRAL,
        FRIENDLY,
        BUSY,
        IMPATIENT,
        FRUSTRATED,
        DISINTERESTED
    }
}

/**
 * Deterministic social-action gates for supported notification replies.
 * Model prompts can shape wording, but cannot override these decisions.
 */
object SocialIntelligenceEngine {
    private val identityQuestion = Regex(
        """\b(?:who\s+are\s+you|who'?s\s+this|are\s+you\s+(?:a\s+)?(?:bot|ai|human|hope|friday|e\.?\s*d\.?\s*i\.?\s*t\.?\s*h\.?|ultron)|(?:what|who)\s+is\s+(?:hope|friday|e\.?\s*d\.?\s*i\.?\s*t\.?\s*h\.?|ultron)|tum\s+kaun|aap\s+kaun)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stopBoundary = Regex(
        """\b(?:stop\s+(?:(?:sending\s+)?unnecessary\s+(?:messages?|communication)|messaging|texting|contacting|replying)|don't\s+(?:message|contact|text)|do\s+not\s+(?:message|contact|text)|not\s+interested|no\s+unnecessary\s+communication|unnecessary\s+communication|we\s+don't\s+have\s+time|wasting\s+(?:my\s+)?time|connect\s+if\s+you'?re\s+genuinely\s+interested)\b""",
        RegexOption.IGNORE_CASE
    )
    private val impatientTone = Regex(
        """\b(?:hurry\s+up|quickly|asap|urgent|immediately|waiting\s+for\s+your\s+response|reply\s+now)\b""",
        RegexOption.IGNORE_CASE
    )
    private val frustratedTone = Regex(
        """\b(?:frustrat\w*|annoyed|irritat\w*|wasting\s+(?:my\s+)?time|unnecessary\s+communication|we\s+don't\s+have\s+time|stop\s+(?:messaging|texting|contacting|replying))\b""",
        RegexOption.IGNORE_CASE
    )
    private val careerQuestion = Regex(
        """\b(?:ctc|salary|compensation|current\s+package|reason\s+for\s+(?:a\s+)?job\s+change|why\s+(?:are\s+you|do\s+you)\s+(?:looking|changing)|joining\s+(?:date|period)|notice\s+period|bond(?:\s+flexibility)?|qualifications?|achievements?|professional\s+experience|expected\s+(?:salary|ctc)|work\s+authorization|knowledge\s+of\s+(?:the\s+)?applied\s+profile|applied\s+profile)\b""",
        RegexOption.IGNORE_CASE
    )
    private val meetingQuestion = Regex(
        """\b(?:meet(?:ing)?|available|availability|schedule|calendar|free\s+(?:on|at|tomorrow)|interview\s+(?:slot|time)|can\s+we\s+(?:talk|meet|schedule))\b""",
        RegexOption.IGNORE_CASE
    )
    private val opportunityQuestion = Regex(
        """\b(?:recruiter|job\s+opportunity|career\s+opportunity|interview|job\s+offer|employment\s+offer|role\s+for\s+you|position\s+for\s+you)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ownerDecisionQuestion = Regex(
        """\b(?:confirm\s+with\s+yash|ask\s+yash|yash'?s\s+approval|does\s+yash\s+want|is\s+yash\s+interested|will\s+yash|can\s+yash\s+confirm|should\s+yash)\b""",
        RegexOption.IGNORE_CASE
    )
    private val meaningfulAfterStop = Regex(
        """\b(?:urgent|deadline|offer|opportunity|interview|meeting|schedule|availability|please\s+confirm|role|job|position|updated\s+details)\b""",
        RegexOption.IGNORE_CASE
    )
    private val lowValueEnd = Regex(
        """^\W*(?:thanks?,?\s+bye|bye,?\s+thanks?|thanks?|thank\s+you|thx|ok(?:ay)?|got\s+it|bye|goodbye|take\s+care|see\s+you|gn|good\s+night|no\s+problem|welcome|lol|haha+|lmao)[.!?\s]*$""",
        RegexOption.IGNORE_CASE
    )
    private val senderBusy = Regex(
        """\b(?:i'?m\s+busy|i\s+am\s+busy|in\s+a\s+meeting|can'?t\s+talk\s+now|call\s+later|message\s+you\s+later|will\s+message\s+later|not\s+available\s+now)\b""",
        RegexOption.IGNORE_CASE
    )
    private val greeting = Regex(
        """^\W*(?:hi+|hello+|hey+|hii+|good\s+(?:morning|afternoon|evening)|namaste)\W*$""",
        RegexOption.IGNORE_CASE
    )

    fun analyze(
        incomingText: String,
        previousMessages: List<SocialMessage> = emptyList(),
        sensitive: Boolean = AutoReplyPolicy.containsSensitiveContent(incomingText)
    ): SocialAnalysis {
        val text = incomingText.trim()
        val repeatedGreeting = greeting.matches(text) &&
            previousMessages.lastOrNull()?.let { isAssistant(it.sender) } == true
        val answeredRepeat = alreadyAnswered(text, previousMessages) ||
            repeatedGreeting ||
            (greeting.matches(text) && alreadyAnswered(text, previousMessages, minWords = 1))
        val isIdentityQuestion = identityQuestion.containsMatchIn(text)
        val isBoundary = stopBoundary.containsMatchIn(text)
        val isCareer = careerQuestion.containsMatchIn(text)
        val isMeeting = meetingQuestion.containsMatchIn(text)
        val isOpportunity = opportunityQuestion.containsMatchIn(text)
        val needsDecision = ownerDecisionQuestion.containsMatchIn(text)
        val isEnd = lowValueEnd.matches(text)
        val isBusy = senderBusy.containsMatchIn(text)
        val isMediaOnly = SmartReplyLogic.detectIntent(text) == SmartReplyLogic.Intent.MEDIA_ONLY
        val tone = when {
            isBoundary -> SocialAnalysis.Tone.DISINTERESTED
            frustratedTone.containsMatchIn(text) -> SocialAnalysis.Tone.FRUSTRATED
            impatientTone.containsMatchIn(text) -> SocialAnalysis.Tone.IMPATIENT
            isBusy -> SocialAnalysis.Tone.BUSY
            Regex("""\b(?:thanks|thank you|great|awesome|looking forward)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) -> SocialAnalysis.Tone.FRIENDLY
            else -> SocialAnalysis.Tone.NEUTRAL
        }

        val ownerReview = sensitive || isCareer || isMeeting || isOpportunity || needsDecision || isBoundary
        val intent = when {
            isIdentityQuestion -> SocialAnalysis.Intent.IDENTITY_QUESTION
            isBoundary -> SocialAnalysis.Intent.STOP_BOUNDARY
            isCareer || isOpportunity || needsDecision -> SocialAnalysis.Intent.CAREER_DECISION
            isMeeting -> SocialAnalysis.Intent.MEETING_REQUEST
            isEnd -> SocialAnalysis.Intent.CONVERSATION_END
            isBusy -> SocialAnalysis.Intent.SENDER_BUSY
            isMediaOnly -> SocialAnalysis.Intent.UNNECESSARY_MESSAGE
            answeredRepeat -> SocialAnalysis.Intent.REPEATED_ANSWERED_QUESTION
            else -> SocialAnalysis.Intent.NORMAL
        }
        val missing = buildList {
            if (isCareer) {
                if (Regex("""\b(?:ctc|salary|compensation|package)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add("verified current and expected compensation")
                }
                if (Regex("""\b(?:joining|notice\s+period|start\s+date)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add("verified availability or notice period")
                }
                if (Regex("""\b(?:job\s+change|looking|changing)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add("Yash's personal reason for considering a change")
                }
                if (isEmpty()) add("verified career information and Yash's approval")
            }
            if (isMeeting) add("Yash's confirmed availability")
            if (isOpportunity || needsDecision) add("Yash's interest and decision about this request")
            if (isBoundary) add("Yash's decision whether any further contact is appropriate")
            if (sensitive) add("Yash's review and approval for this sensitive request")
        }
        val holdingReply = when {
            isBoundary ->
                "Understood. Yash will review the details and connect if interested. Thank you."
            isCareer || isOpportunity || needsDecision ->
                "Thanks for sharing these questions. Yash needs to review them before I can answer."
            isMeeting ->
                "I can't confirm Yash's availability, so I've shared your request with him."
            else -> null
        }
        val shouldRespond = when {
            sensitive -> false
            isBoundary -> true
            isEnd || isBusy || isMediaOnly || answeredRepeat -> false
            else -> true
        }
        val summary = when {
            isBoundary -> "The sender is frustrated or does not want unnecessary follow-up."
            isCareer || isOpportunity -> "The sender is asking about a career matter or opportunity that needs Yash's decision."
            needsDecision -> "The sender is asking for Yash's personal decision or approval."
            isMeeting -> "The sender is asking about Yash's availability or a meeting."
            sensitive -> "The message contains sensitive or high-impact content."
            isIdentityQuestion -> "The sender explicitly asked who they are speaking with."
            isEnd -> "The conversation appears complete; no reply is needed."
            isBusy -> "The sender is unavailable; wait for them to continue."
            isMediaOnly -> "The message has no accessible text; no reply is needed."
            else -> "The sender sent a message that may need a contextual response."
        }
        return SocialAnalysis(
            intent = intent,
            tone = tone,
            shouldRespond = shouldRespond,
            requiresOwnerReview = ownerReview,
            stopAfterResponse = isBoundary,
            sensitive = sensitive,
            summary = summary,
            missingInformation = missing,
            safeHoldingReply = holdingReply
        )
    }

    fun isIdentityQuestion(text: String): Boolean = identityQuestion.containsMatchIn(text)

    fun identityReply(
        language: String,
        message: String,
        assistantName: String = "HOPE",
        ownerName: String = "Yash"
    ): String {
        val identity = assistantName.trim().takeIf { it.isNotBlank() && it.length <= 40 } ?: "HOPE"
        val owner = ownerName.trim().takeIf { it.isNotBlank() && it.length <= 40 } ?: "the phone owner"
        val isHope = identity.equals("HOPE", ignoreCase = true)
        return when (resolveLanguage(language, message)) {
            "Hindi" -> if (isHope) {
                "मैं $identity, $owner की AI assistant हूँ।"
            } else {
                "मैं $identity, HOPE की AI persona हूँ।"
            }
            "Hinglish" -> if (isHope) {
                "Main $identity, $owner ki AI assistant hoon."
            } else {
                "Main $identity, HOPE ki AI persona hoon."
            }
            else -> if (isHope) {
                "I'm $identity, $owner's AI assistant."
            } else {
                "I'm $identity, HOPE's AI persona assisting $owner."
            }
        }
    }

    fun holdingReply(analysis: SocialAnalysis, language: String, message: String): String? =
        when (resolveLanguage(language, message)) {
            "Hindi" -> when (analysis.intent) {
                SocialAnalysis.Intent.STOP_BOUNDARY ->
                    "समझ गया। Yash जानकारी देखेंगे और रुचि होने पर संपर्क करेंगे। धन्यवाद।"
                SocialAnalysis.Intent.CAREER_DECISION ->
                    "ये सवाल Yash की समीक्षा के लिए भेजे हैं; पुष्टि के बिना मैं जवाब नहीं दे सकता।"
                SocialAnalysis.Intent.MEETING_REQUEST ->
                    "मैं Yash की उपलब्धता की पुष्टि नहीं कर सकता; आपका अनुरोध उन्हें भेज दिया है।"
                else -> analysis.safeHoldingReply
            }
            "Hinglish" -> when (analysis.intent) {
                SocialAnalysis.Intent.STOP_BOUNDARY ->
                    "Samajh gaya. Yash details dekh kar interested hue to connect karenge. Thanks."
                SocialAnalysis.Intent.CAREER_DECISION ->
                    "Ye questions Yash review karenge; unki confirmation ke bina main jawab nahi de sakta."
                SocialAnalysis.Intent.MEETING_REQUEST ->
                    "Yash ki availability main confirm nahi kar sakta; aapka request unhe bhej diya hai."
                else -> analysis.safeHoldingReply
            }
            else -> analysis.safeHoldingReply
        }

    fun neutralBoundaryReply(language: String, message: String): String =
        when (resolveLanguage(language, message)) {
            "Hindi" -> "समझ गया। मैं अनावश्यक संदेश नहीं भेजूँगा।"
            "Hinglish" -> "Samajh gaya. Main unnecessary follow-up nahi bhejunga."
            else -> "Understood. I won't send unnecessary follow-ups."
        }

    fun shouldResumeAfterStop(text: String): Boolean =
        meaningfulAfterStop.containsMatchIn(text) ||
            (AutoReplyPolicy.containsSensitiveContent(text) && text.contains('?', ignoreCase = false))

    fun promptGuidance(analysis: SocialAnalysis, includeTone: Boolean = true): String =
        "Social analysis: intent=${analysis.intent.name.lowercase(Locale.ROOT)}, " +
            (if (includeTone) "sender tone=${analysis.tone.name.lowercase(Locale.ROOT)}. " else "") +
            "Use one concise, professional response only when it is useful. Do not repeat an introduction, " +
            "greeting, answered question, or confirmation. If the sender is impatient or frustrated, " +
            "be brief, acknowledge the boundary, do not argue, and do not send follow-ups."

    private fun alreadyAnswered(
        text: String,
        previousMessages: List<SocialMessage>,
        minWords: Int = 3
    ): Boolean {
        val current = normalizedWords(text)
        if (current.size < minWords) return false
        val previousQuestion = previousMessages.indices.lastOrNull { index ->
            val message = previousMessages[index]
            !isAssistant(message.sender) && similarity(current, normalizedWords(message.text)) >= 0.8
        } ?: return false
        return previousMessages.drop(previousQuestion + 1).any { isAssistant(it.sender) }
    }

    private fun isAssistant(sender: String): Boolean =
        sender.equals("HOPE (AI assistant)", ignoreCase = true) ||
            sender.equals("assistant", ignoreCase = true) ||
            PersonaEngine.profiles.any {
                sender.startsWith("${it.identityName} (AI", ignoreCase = true)
            }

    private fun similarity(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    private fun normalizedWords(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length > 1 }
            .toSet()

    private fun resolveLanguage(language: String, message: String): String = when (language) {
        "English", "Hindi", "Hinglish" -> language
        else -> when {
            message.any { it in '\u0900'..'\u097F' } -> "Hindi"
            Regex("""\b(?:kya|hai|hain|nahi|bhai|yaar|haan|kaise|mujhe|aap)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(message) -> "Hinglish"
            else -> "English"
        }
    }
}
