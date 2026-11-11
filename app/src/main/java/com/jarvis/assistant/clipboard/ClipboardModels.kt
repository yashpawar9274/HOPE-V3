package com.jarvis.assistant.clipboard

import android.net.Uri

enum class ClipboardContentType {
    TEXT,
    IMAGE
}

enum class ClipboardTextCategory(val displayName: String) {
    QUESTION("Question"),
    CONVERSATION("Conversation"),
    CODE("Code"),
    DOCUMENT("Document"),
    GENERAL("General")
}

enum class ClipboardOrbState {
    LISTENING,
    CLIPBOARD_READING,
    ANALYZING,
    RESPONDING,
    ERROR,
    IDLE
}

data class ClipboardContent(
    val type: ClipboardContentType,
    val fingerprint: String,
    val text: String? = null,
    val imageUri: Uri? = null,
    val mimeType: String? = null,
    val category: ClipboardTextCategory? = null,
    val isSensitive: Boolean = false,
    val isTooLarge: Boolean = false
)

data class ClipboardUiState(
    val content: ClipboardContent? = null,
    val response: String? = null,
    val error: String? = null,
    val isProcessing: Boolean = false,
    val orbState: ClipboardOrbState = ClipboardOrbState.IDLE
)
