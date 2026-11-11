package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(
    private val preferences: AppPreferences,
    private val currentOwnerUid: () -> String? = { null },
) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String, engineName: String? = null): ChatTurn? {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        val ownerUid = currentOwnerUid()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return null

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.ownerUid == ownerUid &&
                last.userTranscript == cleanUser &&
                last.jarvisResponse == cleanJarvis
            ) {
                return last
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" },
            engineName = engineName,
            ownerUid = ownerUid
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        return newTurn
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }

    fun currentUserTurns(): List<ChatTurn> {
        val ownerUid = currentOwnerUid() ?: return emptyList()
        return _turns.value.filter { it.ownerUid == ownerUid }
    }
}
