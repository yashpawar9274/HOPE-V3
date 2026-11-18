package com.jarvis.assistant.data.memory

import java.text.Normalizer
import java.util.Locale

data class CoreBehaviorCandidate(
    val instructionText: String,
    val normalizedRule: String,
    val category: String,
    val scope: String,
    val priority: Int,
)

object CoreBehaviorPolicy {
    private val permanentLead = Regex(
        """^(?:(?:hey\s+)?hope[,:\s]+)?(?:please\s+)?(?:never\b|always\b|from now on\b|remember (?:this )?permanently\b|permanently remember\b|going forward\b|kabhi\s+mat\b|hamesha\b)""",
        RegexOption.IGNORE_CASE
    )
    private val repeatCorrection = Regex(
        """^(?:(?:hey\s+)?hope[,:\s]+)?(?:please\s+)?(?:don't|do not)\b.*\b(?:again|repeat(?:ed|ing)?|repeatedly|same question|baar-?baar)\b""",
        RegexOption.IGNORE_CASE
    )
    private val hinglishRepeatCorrection = Regex(
        """^(?:(?:hey\s+)?hope[,:\s]+)?(?:please\s+)?(?:.*\b(?:baar-?baar|same question|repeatedly)\b.*\b(?:mat|don't|do not)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val vagueInstructions = setOf(
        "never do this again",
        "always follow this rule",
        "remember this permanently",
        "from now on behave this way",
        "don't repeat this mistake",
        "do not repeat this mistake",
    )
    private val negativeDirective = Regex(
        """\b(never|don't|do not|avoid|must not|can't|cannot|mat|nahi)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stopWords = setOf(
        "always", "never", "dont", "do", "not", "avoid", "must", "can", "cannot",
        "from", "now", "on", "remember", "permanently", "please", "hope", "this",
        "that", "again", "rule", "follow", "mat", "kabhi", "hamesha", "before",
        "after", "check", "checking", "same", "repeatedly",
    )

    fun extract(text: String): CoreBehaviorCandidate? {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 8..2_000 || !isExplicitPermanentInstruction(clean)) return null

        val normalized = canonicalize(clean)
        if (normalized in vagueInstructions) return null

        val scope = when {
            Regex("""\bwhats?app\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> CoreBehaviorRule.SCOPE_WHATSAPP
            Regex("""\bvoice\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> CoreBehaviorRule.SCOPE_VOICE
            Regex("""\b(background agent|agent task)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> CoreBehaviorRule.SCOPE_BACKGROUND_AGENT
            Regex("""\b(text chat|text conversation)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> CoreBehaviorRule.SCOPE_TEXT
            else -> CoreBehaviorRule.SCOPE_GLOBAL
        }
        val category = when {
            scope == CoreBehaviorRule.SCOPE_WHATSAPP -> "whatsapp_communication_rules"
            Regex("""\b(private|privacy|personal data|secret|confidential)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "privacy_preferences"
            negativeDirective.containsMatchIn(clean) -> "permanent_restrictions"
            Regex("""\b(ask|question|tone|language|brief|concise|repeat)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "communication_preferences"
            else -> "task_execution_preferences"
        }
        return CoreBehaviorCandidate(
            instructionText = clean,
            normalizedRule = normalized,
            category = category,
            scope = scope,
            priority = if (negativeDirective.containsMatchIn(clean)) 100 else 60,
        )
    }

    fun isExplicitButAmbiguous(text: String): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return clean.length in 8..2_000 &&
            isExplicitPermanentInstruction(clean) &&
            extract(clean) == null
    }

    private fun isExplicitPermanentInstruction(text: String): Boolean =
        permanentLead.containsMatchIn(text) ||
            repeatCorrection.containsMatchIn(text) ||
            hinglishRepeatCorrection.containsMatchIn(text)

    fun canonicalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s']"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun conflicts(candidate: CoreBehaviorCandidate, existing: CoreBehaviorRule): Boolean {
        if (candidate.scope != CoreBehaviorRule.SCOPE_GLOBAL &&
            existing.scope != CoreBehaviorRule.SCOPE_GLOBAL &&
            candidate.scope != existing.scope
        ) return false
        val candidateTokens = topicTokens(candidate.normalizedRule)
        val existingTokens = topicTokens(existing.normalizedRule)
        val sharedTokens = candidateTokens intersect existingTokens
        val unionSize = (candidateTokens + existingTokens).size
        if (sharedTokens.size < 2 || unionSize == 0 || sharedTokens.size.toDouble() / unionSize < 0.6) {
            return false
        }
        return negativeDirective.containsMatchIn(candidate.normalizedRule) !=
            negativeDirective.containsMatchIn(existing.normalizedRule)
    }

    private fun topicTokens(value: String): Set<String> =
        value.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .map { token ->
                when {
                    token.length > 4 && token.endsWith("s") -> token.removeSuffix("s")
                    token.length > 4 && token.endsWith("ed") -> token.removeSuffix("ed")
                    else -> token
                }
            }
            .toSet()
}
