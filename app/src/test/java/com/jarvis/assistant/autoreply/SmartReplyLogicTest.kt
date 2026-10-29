package com.jarvis.assistant.autoreply

import com.jarvis.assistant.autoreply.SmartReplyLogic.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartReplyLogicTest {
    @Test
    fun commonOpeningMessagesAreUnderstood() {
        assertEquals(Intent.GREETING, SmartReplyLogic.detectIntent("Hi"))
        assertEquals(Intent.GREETING, SmartReplyLogic.detectIntent("Hello!"))
        assertEquals(Intent.GREETING, SmartReplyLogic.detectIntent("hii bhai"))
        assertEquals(Intent.GREETING, SmartReplyLogic.detectIntent("Good morning"))
        assertEquals(Intent.ASKING_WHAT_TO_DO, SmartReplyLogic.detectIntent("kya karu ab?"))
        assertEquals(Intent.ASKING_WHAT_TO_DO, SmartReplyLogic.detectIntent("what should I do now"))
        assertEquals(Intent.ASKING_WHAT_TO_DO, SmartReplyLogic.detectIntent("hi kya karu"))
        assertEquals(Intent.HOW_ARE_YOU, SmartReplyLogic.detectIntent("kaise ho bhai"))
        assertEquals(Intent.THANKS, SmartReplyLogic.detectIntent("thanks a lot"))
        assertEquals(Intent.GOODBYE, SmartReplyLogic.detectIntent("ok bye"))
        assertEquals(Intent.QUESTION, SmartReplyLogic.detectIntent("Are you free tomorrow?"))
    }

    @Test
    fun mediaOnlyAndEmptyMessagesAreHandled() {
        assertEquals(Intent.MEDIA_ONLY, SmartReplyLogic.detectIntent("\uD83D\uDCF7 Photo"))
        assertEquals(Intent.MEDIA_ONLY, SmartReplyLogic.detectIntent("\uD83D\uDE00\uD83D\uDE00"))
        assertEquals(Intent.OTHER, SmartReplyLogic.detectIntent("   "))
    }

    @Test
    fun longMessageStartingWithHelloIsNotJustAGreeting() {
        assertTrue(
            SmartReplyLogic.detectIntent("hello I wanted to tell you about the meeting notes from yesterday") !=
                Intent.GREETING
        )
    }

    @Test
    fun promptMakesHopeIntroduceItselfAndKeepsSafetyRules() {
        val prompt = SmartReplyLogic.buildSystemInstruction("YASH", "Auto", "", "WhatsApp", Intent.GREETING)
        assertTrue(prompt.contains("You are HOPE"))
        assertTrue(prompt.contains("assistant of YASH"))
        assertTrue(prompt.contains("untrusted data"))
        assertTrue(prompt.contains("Never pretend to be YASH or a human"))
        assertTrue(SmartReplyLogic.buildSystemInstruction(" ", "Auto", "", "SMS", Intent.OTHER).contains("the phone owner"))
        assertTrue(SmartReplyLogic.buildSystemInstruction("YASH", "Hinglish", "", "SMS", Intent.OTHER).contains("Hinglish"))
    }

    @Test
    fun firstReplyAlwaysNamesHope() {
        assertTrue(
            SmartReplyLogic.enforceIdentity("Hello! How can I help?", "YASH", true)
                .startsWith("HOPE here, YASH's assistant.")
        )
        assertEquals(
            "Hi, main HOPE hoon, YASH ka assistant.",
            SmartReplyLogic.enforceIdentity("Hi, main HOPE hoon, YASH ka assistant.", "YASH", true)
        )
        assertEquals("Sure, noted.", SmartReplyLogic.enforceIdentity("Sure, noted.", "YASH", false))
        assertEquals("Hello", SmartReplyLogic.enforceIdentity("\"Hello\"", "YASH", false))
        assertTrue(SmartReplyLogic.enforceIdentity("a".repeat(900), "YASH", false).length <= SmartReplyLogic.MAX_REPLY_LENGTH)
    }

    @Test
    fun otherAutoRespondersAreNotAnswered() {
        assertTrue(SmartReplyLogic.looksLikeAutomatedMessage("This is an automated reply, I am currently unavailable"))
        assertTrue(SmartReplyLogic.looksLikeAutomatedMessage("HOPE here, Rahul's assistant. Hello!"))
        assertFalse(SmartReplyLogic.looksLikeAutomatedMessage("bhai kal milte hain?"))
    }

    @Test
    fun throttleEnforcesCooldownAndHourlyCap() {
        val throttle = SmartReplyLogic.ReplyThrottle()
        assertTrue(throttle.canReply("c1", 1_000))
        throttle.record("c1", 1_000)
        assertFalse(throttle.canReply("c1", 6_000))
        assertTrue(throttle.canReply("c1", 22_000))
        assertTrue(throttle.canReply("c2", 2_000))
        var now = 22_000L
        repeat(5) {
            throttle.record("c1", now)
            now += 25_000
        }
        assertFalse(throttle.canReply("c1", now + 25_000))
        assertTrue(throttle.canReply("c1", now + 3_700_000))
    }

    @Test
    fun introIsRepeatedOnlyAfterTwelveHours() {
        val tracker = SmartReplyLogic.IntroTracker()
        assertTrue(tracker.needsIntro("c1", 0))
        tracker.markIntroduced("c1", 0)
        assertFalse(tracker.needsIntro("c1", 60_000))
        assertTrue(tracker.needsIntro("c1", SmartReplyLogic.INTRO_WINDOW_MILLIS + 1))
    }

    @Test
    fun groupChatsAreNeverAutoReplied() {
        assertTrue(SmartAutoReply.isGroupConversation("com.whatsapp", true, null))
        assertTrue(SmartAutoReply.isGroupConversation("com.whatsapp", false, "Family group"))
        assertFalse(SmartAutoReply.isGroupConversation("com.whatsapp", false, null))
        assertFalse(SmartAutoReply.isGroupConversation("com.google.android.apps.messaging", false, "Rahul"))
    }
}
