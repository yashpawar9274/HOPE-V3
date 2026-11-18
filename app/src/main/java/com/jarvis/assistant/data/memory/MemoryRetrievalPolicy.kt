package com.jarvis.assistant.data.memory

import java.util.Locale

internal object MemoryRetrievalPolicy {
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")
    private val ignoredTerms = setOf(
        "about", "after", "again", "and", "are", "for", "from", "have", "how",
        "into", "just", "me", "more", "most", "my", "our", "please", "said",
        "tell", "that", "the", "their", "them", "then", "there", "this", "what",
        "when", "where", "which", "who", "why", "with", "you", "your"
    )

    fun selectRelevant(
        query: String,
        memories: List<MemoryEntity>,
        limit: Int = 6
    ): List<MemoryEntity> {
        require(limit >= 0) { "Memory result limit cannot be negative." }
        if (limit == 0) return emptyList()

        val queryTerms = tokens(query).filterTo(mutableSetOf()) {
            it.length >= 3 && it !in ignoredTerms
        }
        if (queryTerms.isEmpty()) return emptyList()

        return memories.asSequence()
            .filter { it.verificationStatus != MemoryEntity.STATUS_SUPERSEDED }
            .filterNot { it.key.equals("user_location", ignoreCase = true) }
            .map { memory ->
                val memoryTerms = tokens("${memory.key} ${memory.value} ${memory.rawText}")
                memory to queryTerms.count(memoryTerms::contains)
            }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                    .thenByDescending { it.first.timestamp }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun tokens(text: String): Set<String> =
        tokenPattern.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()
}
