package com.jarvis.assistant.autoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyPolicyTest {
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
    fun credentialsAndPaymentContentAreFiltered() {
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Your verification code is 584923"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Send your UPI payment now"))
        assertTrue(AutoReplyPolicy.containsSensitiveContent("Enter the password"))
        assertFalse(AutoReplyPolicy.containsSensitiveContent("Are we still meeting for lunch?"))
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
