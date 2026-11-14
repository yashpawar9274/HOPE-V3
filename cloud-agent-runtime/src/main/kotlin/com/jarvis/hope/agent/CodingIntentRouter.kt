package com.jarvis.hope.agent

import java.util.Locale

internal enum class AgentRequestRoute {
    CONVERSATION,
    CODING,
}

internal object CodingIntentRouter {
    private val actionPattern = Regex(
        """\b(build|create|make|write|generate|implement|develop|fix|debug|refactor|review|test|compile|deploy|help|bana|banao|banade|likh|likho|sudhar|theek)\b""",
    )
    private val developmentPattern = Regex(
        """\b(code|coding|program|programming|developer|repository|repo|website|web\s+app|kotlin|java|javascript|typescript|python|html|css|react|next\.?js|bug|error|gradle|api|github|project)\b""",
    )
    private val appDevelopmentPattern = Regex(
        """\b(build|create|make|write|generate|implement|develop|fix|debug|refactor|review|test|compile|deploy|bana|banao|banade|likh|likho|sudhar|theek)\b.*\b(app|android|ios|mobile)\b|\b(app|android|ios|mobile)\b.*\b(build|create|make|write|generate|implement|develop|fix|debug|refactor|review|test|compile|deploy|bana|banao|banade|likh|likho|sudhar|theek)\b""",
    )

    fun route(request: String): AgentRequestRoute {
        val normalized = request.lowercase(Locale.ROOT)
        return if (
            actionPattern.containsMatchIn(normalized) &&
            (developmentPattern.containsMatchIn(normalized) ||
                appDevelopmentPattern.containsMatchIn(normalized))
        ) {
            AgentRequestRoute.CODING
        } else {
            AgentRequestRoute.CONVERSATION
        }
    }
}
