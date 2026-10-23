package com.jarvis.assistant.autoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyPolicyTest {
    @Test
    fun explicitWhatsappAutoReplyCommandsAreRecognized() {
        assertEquals(
            WhatsAppAutoReplyCommand.START,
            WhatsAppAutoReplyCommandPolicy.detect(
                "HOPE, WhatsApp par mere incoming messages check karo aur unka reply khud se do"
            )
        )
        assertEquals(
            WhatsAppAutoReplyCommand.START,
            WhatsAppAutoReplyCommandPolicy.detect("WhatsApp auto-reply start karo")
        )
        assertEquals(
            WhatsAppAutoReplyCommand.STOP,
            WhatsAppAutoReplyCommandPolicy.detect("WhatsApp auto-reply band karo")
        )
        assertEquals(
            WhatsAppAutoReplyCommand.STATUS,
            WhatsAppAutoReplyCommandPolicy.detect("Auto-reply status batao")
        )
    }

    @Test
    fun ordinaryWhatsappMessagesDoNotTriggerAutoReplyControl() {
        assertEquals(null, WhatsAppAutoReplyCommandPolicy.detect("Send a WhatsApp message to Mom"))
        assertEquals(null, WhatsAppAutoReplyCommandPolicy.detect("How does WhatsApp auto-reply work?"))
    }

    @Test
    fun onlySupportedMessagingPackagesAreAccepted() {
        assertTrue(AutoReplyPolicy.isSupportedPackage("com.whatsapp", null))
        assertTrue(AutoReplyPolicy.isSupportedPackage("com.whatsapp.w4b", null))
        assertTrue(AutoReplyPolicy.isSupportedPackage("com.instagram.android", null))
        assertTrue(AutoReplyPolicy.isSupportedPackage("com.carrier.sms", "com.carrier.sms"))
        assertFalse(AutoReplyPolicy.isSupportedPackage("com.example.other", null))
        assertFalse(AutoReplyPolicy.isSupportedPackage("com.example.other", "com.carrier.sms"))
    }

    @Test
    fun chatIdentityIsCaseInsensitiveAndPackageScoped() {
        assertEquals(
            AutoReplyPolicy.chatKey("com.whatsapp", "Family"),
            AutoReplyPolicy.chatKey("COM.WHATSAPP", " family ")
        )
        assertFalse(
            AutoReplyPolicy.chatKey("com.whatsapp", "Family") ==
                AutoReplyPolicy.chatKey("com.instagram.android", "Family")
        )
    }

    @Test
    fun enabledAppsIncludeAllConversationsExceptExplicitExclusions() {
        val excluded = listOf(AutoReplyChat("com.whatsapp", "NoReply"))

        assertTrue(AutoReplyPolicy.isChatAllowed("com.whatsapp", "Family", excluded))
        assertFalse(AutoReplyPolicy.isChatAllowed("COM.WHATSAPP", " noreply ", excluded))
        assertTrue(AutoReplyPolicy.isChatAllowed("com.instagram.android", "NoReply", excluded))
    }

    @Test
    fun messagingStyleTextFallsBackToLatestIncomingMessage() {
        assertEquals(
            "Can you call me?",
            AutoReplyPolicy.notificationTextOrLatestMessage("", listOf("Earlier", "Can you call me?"))
        )
        assertEquals(
            "Visible preview",
            AutoReplyPolicy.notificationTextOrLatestMessage(" Visible preview ", listOf("Fallback"))
        )
        assertEquals(
            "MessagingStyle incoming message",
            AutoReplyPolicy.notificationTextOrLatestMessage(
                "",
                listOf("MessagingStyle incoming message")
            )
        )
        assertEquals("", AutoReplyPolicy.notificationTextOrLatestMessage("", emptyList()))
    }

    @Test
    fun credentialsAndPaymentContentAreFiltered() {
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Your verification code is 584923"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Send your UPI payment now"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Enter the password"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Your bank account was debited"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Security alert: new login detected"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("I need legal advice about this court case"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Can you recommend a prescription medicine?"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("आपके खाते में रुपये जमा हुए"))
        assertFalse(AutoReplyPolicy.containsSensitiveContent("Are we still meeting for lunch?"))
    }

    @Test
    fun onlyAnExplicitAuthorizedReplyRuleCanPermitAutomaticSending() {
        val suggestion = NotificationRule(
            name = "Draft only",
            action = NotificationRuleAction.SUGGEST_REPLY
        )
        val authorized = NotificationRule(
            name = "Explicitly authorized",
            action = NotificationRuleAction.AUTHORIZED_AUTO_REPLY
        )

        assertEquals(null, NotificationRuleEngine.authorizedAutoReplyRule(emptyList()))
        assertEquals(null, NotificationRuleEngine.authorizedAutoReplyRule(listOf(suggestion)))
        assertEquals(authorized, NotificationRuleEngine.authorizedAutoReplyRule(listOf(suggestion, authorized)))
    }

    @Test
    fun notificationUpdatesPreserveReadStateOnlyForUnchangedContent() {
        val existing = NotificationRecord(
            id = "stable",
            packageName = "com.example.chat",
            appName = "Chat",
            sender = "Aarav",
            message = "Hello",
            receivedAt = 100L,
            sensitive = false,
            notificationKey = "notification-key",
            isRead = true,
            dismissed = true
        )
        val unchanged = NotificationInboxPolicy.upsert(
            existing,
            existing.copy(receivedAt = 200L, dismissed = false)
        )
        val updatedMessage = NotificationInboxPolicy.upsert(
            existing,
            existing.copy(message = "A new message", receivedAt = 300L)
        )

        assertTrue(unchanged.isRead)
        assertFalse(unchanged.dismissed)
        assertFalse(updatedMessage.isRead)
        assertFalse(updatedMessage.dismissed)
        assertEquals("Aarav", updatedMessage.sender)
    }

    @Test
    fun separateNotificationKeysRemainSeparateInboxEntries() {
        val first = NotificationRecord(
            id = "first-id",
            packageName = "com.example.chat",
            appName = "Chat",
            sender = "Aarav",
            message = "First message",
            receivedAt = 100L,
            sensitive = false,
            notificationKey = "first-key"
        )
        val second = first.copy(
            id = "second-id",
            sender = "Mira",
            message = "Second message",
            notificationKey = "second-key"
        )
        val update = first.copy(message = "Updated first message", receivedAt = 300L)

        val history = NotificationInboxPolicy.upsertHistory(
            NotificationInboxPolicy.upsertHistory(listOf(first), second, 100),
            update,
            100
        )

        assertEquals(2, history.size)
        assertEquals(listOf("first-key", "second-key"), history.map(NotificationRecord::notificationKey))
        assertEquals("Updated first message", history.first().message)
    }

    @Test
    fun notificationRulesMatchFiltersAndSortByPriority() {
        val highPriority = NotificationRule(
            name = "High priority",
            packageName = "com.whatsapp",
            keyword = "meeting",
            action = NotificationRuleAction.DISPLAY,
            priority = 10
        )
        val lowerPriority = NotificationRule(
            name = "Lower priority",
            packageName = "*",
            action = NotificationRuleAction.ANNOUNCE,
            priority = 1
        )
        val unrelated = NotificationRule(
            name = "Unrelated",
            packageName = "com.instagram.android",
            action = NotificationRuleAction.IGNORE
        )

        val matches = NotificationRuleEngine.matchingRules(
            NotificationRuleInput("com.whatsapp", "Aarav", "Meeting at 6?"),
            listOf(lowerPriority, unrelated, highPriority)
        )

        assertEquals(listOf(highPriority, lowerPriority), matches)
    }

    @Test
    fun disabledRulesAndSenderFiltersDoNotMatch() {
        val disabled = NotificationRule(
            name = "Disabled",
            action = NotificationRuleAction.ANNOUNCE,
            enabled = false
        )
        val wrongSender = NotificationRule(
            name = "Wrong sender",
            sender = "Mira",
            action = NotificationRuleAction.ANNOUNCE
        )

        assertTrue(
            NotificationRuleEngine.matchingRules(
                NotificationRuleInput("com.whatsapp", "Aarav", "Hello"),
                listOf(disabled, wrongSender)
            ).isEmpty()
        )
    }

    @Test
    fun templatesReplaceSupportedPlaceholdersAndRespectMaximumLength() {
        val rendered = NotificationRuleEngine.renderTemplate(
            "{app_name} from {sender}: {message} at {time}; reply={suggested_reply}",
            "WhatsApp",
            "Aarav",
            "Hello",
            "18:30",
            "Thanks"
        )
        assertEquals("WhatsApp from Aarav: Hello at 18:30; reply=Thanks", rendered)
        assertEquals(1000, NotificationRuleEngine.renderTemplate("x".repeat(1100), "", "", "", "").length)
    }

    @Test
    fun quietHoursHandleDayAndOvernightRangesWithExclusiveEnd() {
        assertTrue(NotificationRuleEngine.isQuietAt(9 * 60, 17 * 60, 9 * 60))
        assertTrue(NotificationRuleEngine.isQuietAt(9 * 60, 17 * 60, 16 * 60 + 59))
        assertFalse(NotificationRuleEngine.isQuietAt(9 * 60, 17 * 60, 17 * 60))
        assertTrue(NotificationRuleEngine.isQuietAt(22 * 60, 7 * 60, 23 * 60))
        assertTrue(NotificationRuleEngine.isQuietAt(22 * 60, 7 * 60, 6 * 60 + 59))
        assertFalse(NotificationRuleEngine.isQuietAt(22 * 60, 7 * 60, 7 * 60))
        assertFalse(NotificationRuleEngine.isQuietAt(480, 480, 480))
    }
}
