package com.jarvis.assistant.meeting

import com.jarvis.assistant.meeting.MeetingModeLogic.Decision
import com.jarvis.assistant.meeting.MeetingModeLogic.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingModeLogicTest {
    @Test
    fun modeExpiresAtTheChosenTime() {
        assertTrue(MeetingModeLogic.isActive(true, 0, 5))
        assertTrue(MeetingModeLogic.isActive(true, 1000, 999))
        assertFalse(MeetingModeLogic.isActive(true, 1000, 1000))
        assertFalse(MeetingModeLogic.isActive(false, 0, 5))
    }

    @Test
    fun ordinaryCallsAreScreenedButStarredAndRepeatedCallsRing() {
        assertEquals(Decision.NOT_ACTIVE, MeetingModeLogic.decide(false, false, true, true, 5))
        assertEquals(Decision.SCREEN, MeetingModeLogic.decide(true, false, true, true, 0))
        assertEquals(Decision.RING_THROUGH_STARRED, MeetingModeLogic.decide(true, true, true, true, 0))
        assertEquals(Decision.SCREEN, MeetingModeLogic.decide(true, true, false, true, 0))
        assertEquals(Decision.RING_THROUGH_REPEATED, MeetingModeLogic.decide(true, false, true, true, 1))
        assertEquals(Decision.SCREEN, MeetingModeLogic.decide(true, false, true, false, 1))
    }

    @Test
    fun sameCallerIsRecognisedAcrossNumberFormats() {
        assertEquals("9876543210", MeetingModeLogic.normalizeNumber("+91 98765-43210"))
        assertEquals("9876543210", MeetingModeLogic.normalizeNumber("09876543210"))
        assertEquals("", MeetingModeLogic.normalizeNumber(""))
    }

    @Test
    fun repeatWindowIsFiveMinutes() {
        val tracker = MeetingModeLogic.RecentCallTracker()
        assertEquals(0, tracker.countWithin("a", MeetingModeLogic.REPEAT_WINDOW_MS, 1000))
        tracker.record("a", 1000)
        assertEquals(1, tracker.countWithin("a", MeetingModeLogic.REPEAT_WINDOW_MS, 100_000))
        assertEquals(0, tracker.countWithin("a", MeetingModeLogic.REPEAT_WINDOW_MS, 1000 + MeetingModeLogic.REPEAT_WINDOW_MS + 1))
        assertEquals(0, tracker.countWithin("b", MeetingModeLogic.REPEAT_WINDOW_MS, 1500))
    }

    @Test
    fun smsPromptCarriesIdentitySituationAndOwnerInstructions() {
        val prompt = MeetingModeLogic.buildCallerSmsPrompt(
            "YASH", Situation.SLEEPING, "", "Be very polite and formal.", "Rahul", "Auto", true
        )
        assertTrue(prompt.contains("You are HOPE"))
        assertTrue(prompt.contains("assistant of YASH"))
        assertTrue(prompt.contains("kar rahi hoon"))
        assertTrue(prompt.contains("Be very polite and formal."))
        assertTrue(prompt.contains("Rahul"))
        assertTrue(prompt.contains("220 characters"))
    }

    @Test
    fun ownerInstructionsAreCleanedAndCapped() {
        assertEquals(MeetingModeLogic.MAX_CUSTOM_PROMPT_CHARS, MeetingModeLogic.cleanCustomPrompt("a".repeat(5000)).length)
        assertEquals("hithere", MeetingModeLogic.cleanCustomPrompt("hi\u0001there "))
        assertEquals("", MeetingModeLogic.ownerInstructionsBlock("   "))
    }

    @Test
    fun fallbackSmsStillIntroducesHope() {
        val text = MeetingModeLogic.fallbackCallerSms("YASH", Situation.MEETING)
        assertTrue(text.contains("HOPE") && text.contains("YASH") && text.contains("meeting"))
        assertTrue(text.length < MeetingModeLogic.MAX_SMS_CHARS)
    }
}
