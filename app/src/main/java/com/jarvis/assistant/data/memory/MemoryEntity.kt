package com.jarvis.assistant.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0.5")
    val confidence: Float = 0.5f,
    @ColumnInfo(defaultValue = "'needs_confirmation'")
    val verificationStatus: String = STATUS_NEEDS_CONFIRMATION,
    @ColumnInfo(defaultValue = "'legacy_unverified'")
    val sourceType: String = SOURCE_LEGACY_UNVERIFIED,
    val sourceConversationId: String? = null,
    val sourceTurnId: String? = null,
) {
    companion object {
        const val STATUS_USER_CONFIRMED = "user_confirmed"
        const val STATUS_NEEDS_CONFIRMATION = "needs_confirmation"
        const val STATUS_INFERRED = "inferred"
        const val STATUS_SUPERSEDED = "superseded"

        const val SOURCE_USER_STATEMENT = "user_statement"
        const val SOURCE_USER_CORRECTION = "user_correction"
        const val SOURCE_USER_ACTION = "user_action"
        const val SOURCE_LEGACY_UNVERIFIED = "legacy_unverified"

        const val CATEGORY_PERSONAL = "Personal"
        const val CATEGORY_PREFERENCES = "Preferences"
        const val CATEGORY_CONTACTS = "Contacts"
        const val CATEGORY_ROUTINES = "Routines"
        const val CATEGORY_NOTES = "Notes"

        val ALL_CATEGORIES = listOf(
            CATEGORY_PERSONAL,
            CATEGORY_PREFERENCES,
            CATEGORY_CONTACTS,
            CATEGORY_ROUTINES,
            CATEGORY_NOTES
        )
    }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
