package com.jarvis.assistant.autoreply

data class ReplyActionCandidate(
    val index: Int,
    val hasFreeFormInput: Boolean,
    val isSemanticReply: Boolean,
    val title: String
)

object ReplyActionSelector {
    private val replyTitle = Regex("""\b(reply|send|respond)\b""", RegexOption.IGNORE_CASE)

    fun selectIndex(candidates: List<ReplyActionCandidate>): Int? {
        val eligible = candidates.filter(ReplyActionCandidate::hasFreeFormInput)
        val semanticReplies = eligible.filter(ReplyActionCandidate::isSemanticReply)
        if (semanticReplies.size == 1) return semanticReplies.single().index
        if (semanticReplies.size > 1) return null

        val titledReplies = eligible.filter { replyTitle.containsMatchIn(it.title) }
        return when {
            titledReplies.size == 1 -> titledReplies.single().index
            titledReplies.size > 1 -> null
            eligible.size == 1 -> eligible.single().index
            else -> null
        }
    }
}
