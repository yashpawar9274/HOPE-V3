package com.jarvis.assistant.autoreply

import kotlinx.coroutines.flow.Flow

class ContactReplyProfileRepository(private val dao: ContactReplyProfileDao) {
    val profiles: Flow<List<ContactReplyProfile>> = dao.observeProfiles()
    val discoveredContacts: Flow<List<DiscoveredReplyContact>> = dao.observeDiscoveredContacts()

    suspend fun findProfile(platformPackage: String, identityKey: String): ContactReplyProfile? =
        dao.findProfile(platformPackage, identityKey)

    suspend fun saveProfile(profile: ContactReplyProfile) {
        require(profile.platformPackage.isNotBlank()) { "Platform package must not be blank." }
        require(profile.identityKey.isNotBlank()) { "Contact identity must not be blank." }
        require(profile.mode in ReplyProfileMode.entries.map(ReplyProfileMode::name)) {
            "Unsupported contact reply mode."
        }
        require(profile.identityKind in ReplyIdentityKind.entries.map(ReplyIdentityKind::name)) {
            "Unsupported contact identity kind."
        }
        require(profile.displayName.isNotBlank() && profile.displayName.length <= 120) {
            "Contact label must contain 1 to 120 characters."
        }
        require(profile.tone.length <= 120) { "Contact tone must be 120 characters or fewer." }
        require(profile.instructions.length <= 500) {
            "Contact guidance must be 500 characters or fewer."
        }
        require(profile.replyLanguage in setOf("Auto", "English", "Hindi", "Hinglish")) {
            "Unsupported contact reply language."
        }
        require(profile.maxReplyCharacters in 40..500) {
            "Reply length must be between 40 and 500 characters."
        }
        require(
            profile.mode != ReplyProfileMode.LIMITED_AUTO_REPLY.name ||
                profile.userConfirmedAutoReply
        ) { "Limited auto-reply requires explicit user confirmation." }
        dao.saveProfile(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProfile(profileId: Long) {
        require(profileId > 0) { "Profile ID must be positive." }
        dao.deleteProfile(profileId)
    }

    suspend fun clearLocalData() {
        dao.clearProfiles()
        dao.clearDiscoveredContacts()
        dao.clearReplyEvents()
    }

    suspend fun recordDiscoveredContact(
        platformPackage: String,
        identity: ReplyContactIdentity,
        now: Long = System.currentTimeMillis()
    ) {
        val discoveryKey = "$platformPackage:${identity.key}"
        dao.recordDiscoveredContact(
            DiscoveredReplyContact(
                discoveryKey = discoveryKey,
                platformPackage = platformPackage,
                identityKey = identity.key,
                identityKind = identity.kind.name,
                displayName = identity.displayName.take(120),
                lastSeenAt = now
            )
        )
    }

    suspend fun recordEvent(event: ReplyEventMetadata) {
        require(event.eventToken.isNotBlank()) { "Reply event token must not be blank." }
        dao.recordReplyEvent(event)
        dao.deleteReplyEventsBefore(
            event.createdAt - DEFAULT_EVENT_RETENTION_MILLIS
        )
    }

    suspend fun pruneEvents(retentionMillis: Long = DEFAULT_EVENT_RETENTION_MILLIS) {
        require(retentionMillis > 0) { "Event retention must be positive." }
        dao.deleteReplyEventsBefore(System.currentTimeMillis() - retentionMillis)
    }

    companion object {
        const val DEFAULT_EVENT_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}
