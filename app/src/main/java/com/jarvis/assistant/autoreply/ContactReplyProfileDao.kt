package com.jarvis.assistant.autoreply

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactReplyProfileDao {
    @Query("SELECT * FROM contact_reply_profiles ORDER BY displayName COLLATE NOCASE")
    fun observeProfiles(): Flow<List<ContactReplyProfile>>

    @Query(
        "SELECT * FROM contact_reply_profiles " +
            "WHERE platformPackage = :platformPackage AND identityKey = :identityKey LIMIT 1"
    )
    suspend fun findProfile(platformPackage: String, identityKey: String): ContactReplyProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: ContactReplyProfile): Long

    @Query("DELETE FROM contact_reply_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("SELECT * FROM discovered_reply_contacts ORDER BY lastSeenAt DESC")
    fun observeDiscoveredContacts(): Flow<List<DiscoveredReplyContact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordDiscoveredContact(contact: DiscoveredReplyContact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordReplyEvent(event: ReplyEventMetadata)

    @Query("DELETE FROM reply_event_metadata WHERE createdAt < :before")
    suspend fun deleteReplyEventsBefore(before: Long): Int

    @Query("DELETE FROM contact_reply_profiles")
    suspend fun clearProfiles()

    @Query("DELETE FROM discovered_reply_contacts")
    suspend fun clearDiscoveredContacts()

    @Query("DELETE FROM reply_event_metadata")
    suspend fun clearReplyEvents()
}
