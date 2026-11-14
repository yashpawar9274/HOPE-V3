package com.jarvis.assistant.autoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyContactIdentityResolverTest {
    @Test
    fun samePhoneNumberUsesSameIdentityWithoutStoringTheNumber() {
        val first = ReplyContactIdentityResolver.resolve(
            platformPackage = "com.example.chat",
            shortcutId = null,
            phoneAddress = "tel:+1 (415) 555-0132",
            notificationKey = "notification-1",
            displayName = "Alex"
        )
        val second = ReplyContactIdentityResolver.resolve(
            platformPackage = "com.example.chat",
            shortcutId = null,
            phoneAddress = "+14155550132",
            notificationKey = "notification-2",
            displayName = "Alex Smith"
        )

        assertEquals(first.key, second.key)
        assertEquals(ReplyIdentityKind.PHONE_NUMBER, first.kind)
        assertTrue(first.key.startsWith("phone:"))
        assertTrue(!first.key.contains("415"))
    }

    @Test
    fun notificationKeysDoNotMergeContactsByDisplayName() {
        val first = ReplyContactIdentityResolver.resolve(
            "com.example.chat", null, null, "notification-1", "Alex"
        )
        val second = ReplyContactIdentityResolver.resolve(
            "com.example.chat", null, null, "notification-2", "Alex"
        )

        assertNotEquals(first.key, second.key)
        assertEquals(ReplyIdentityKind.NOTIFICATION_INSTANCE, first.kind)
    }

    @Test
    fun appConversationIdentityIsScopedToPlatform() {
        val first = ReplyContactIdentityResolver.resolve(
            "com.example.chat", "conversation-1", null, "notification-1", "Alex"
        )
        val second = ReplyContactIdentityResolver.resolve(
            "com.other.chat", "conversation-1", null, "notification-2", "Alex"
        )

        assertNotEquals(first.key, second.key)
        assertEquals(ReplyIdentityKind.APP_CONVERSATION, first.kind)
    }

    @Test
    fun invalidPhoneAddressFallsBackToNotificationIdentity() {
        assertNull(ReplyContactIdentityResolver.normalizePhoneAddress("tel:1234"))
    }
}
