package com.jarvis.assistant.data.memory

internal object MemoryQueryPolicy {
    fun escapeLikePattern(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
