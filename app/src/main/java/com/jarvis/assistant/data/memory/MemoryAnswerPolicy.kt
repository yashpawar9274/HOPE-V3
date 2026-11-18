package com.jarvis.assistant.data.memory

internal object MemoryAnswerPolicy {
    fun confirmedValue(savedValue: String, isConfirmed: Boolean): String? =
        savedValue.trim().takeIf { isConfirmed && it.isNotEmpty() }

    fun favoriteSongResponse(song: String): String =
        song.trim().takeIf(String::isNotEmpty)
            ?.let { "Sir, your favorite song is $it." }
            ?: "I don't know your favorite song yet, Sir."
}
