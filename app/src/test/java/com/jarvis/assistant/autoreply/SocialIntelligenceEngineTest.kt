package com.jarvis.assistant.autoreply

import com.jarvis.assistant.ai.SocialIntelligenceGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialIntelligenceEngineTest {
    @Test
    fun recruiterQuestionsAreEscalatedWithoutInventingCareerFacts() {
        val analysis = SocialIntelligenceEngine.analyze(
            "What is your current CTC, reason for job change, notice period, and bond flexibility?"
        )

        assertEquals(SocialAnalysis.Intent.CAREER_DECISION, analysis.intent)
        assertTrue(analysis.requiresOwnerReview)
        assertTrue(analysis.missingInformation.any { it.contains("compensation") })
        assertTrue(analysis.missingInformation.any { it.contains("notice period") })
        assertTrue(analysis.missingInformation.any { it.contains("reason") })
        assertTrue(analysis.safeHoldingReply.orEmpty().contains("review"))
    }

    @Test
    fun impatientBoundaryGetsOneAcknowledgementAndStopsFurtherReplies() {
        val analysis = SocialIntelligenceEngine.analyze(
            "We don't have time for unnecessary communication. If you're genuinely interested, connect with us."
        )

        assertEquals(SocialAnalysis.Intent.STOP_BOUNDARY, analysis.intent)
        assertEquals(SocialAnalysis.Tone.DISINTERESTED, analysis.tone)
        assertTrue(analysis.shouldRespond)
        assertTrue(analysis.stopAfterResponse)
        assertTrue(analysis.safeHoldingReply.orEmpty().contains("connect if interested"))
    }

    @Test
    fun identityIsSharedOnlyWhenExplicitlyAsked() {
        assertTrue(SocialIntelligenceEngine.isIdentityQuestion("Who are you?"))
        assertTrue(SocialIntelligenceEngine.isIdentityQuestion("Who is FRIDAY?"))
        assertTrue(SocialIntelligenceEngine.isIdentityQuestion("Are you E.D.I.T.H.?"))
        assertEquals(
            "I'm HOPE, Yash's AI assistant.",
            SocialIntelligenceEngine.identityReply("English", "Who are you?")
        )
        assertTrue(SocialIntelligenceEngine.identityReply("Hinglish", "Tum kaun ho?").contains("Main HOPE"))
        assertEquals(
            "I'm FRIDAY, HOPE's AI persona assisting Yash.",
            SocialIntelligenceEngine.identityReply(
                "English",
                "Who are you?",
                assistantName = "FRIDAY"
            )
        )
        assertTrue(
            SocialIntelligenceEngine.identityReply(
                "Hinglish",
                "Tum kaun ho?",
                assistantName = "ULTRON"
            ).contains("Main ULTRON")
        )
    }

    @Test
    fun coreBehaviorGuidanceIsSharedAcrossTextVoiceWhatsappAndBackground() {
        val surfaces = listOf("text", "voice", "whatsapp", "background_agent")

        assertTrue(surfaces.all { SocialIntelligenceGuidance.instructionsFor(it).contains("Never introduce") })
        assertTrue(SocialIntelligenceGuidance.instructionsFor("voice").contains("currently selected persona name"))
        assertTrue(SocialIntelligenceGuidance.instructionsFor("whatsapp").contains("Do not impersonate"))
    }

    @Test
    fun meetingAvailabilityAndCareerOpportunitiesRequireYashsDecision() {
        val meeting = SocialIntelligenceEngine.analyze("Can we schedule a meeting tomorrow?")
        val opportunity = SocialIntelligenceEngine.analyze("I have a job opportunity for you.")

        assertEquals(SocialAnalysis.Intent.MEETING_REQUEST, meeting.intent)
        assertTrue(meeting.requiresOwnerReview)
        assertTrue(meeting.missingInformation.any { it.contains("availability") })
        assertEquals(SocialAnalysis.Intent.CAREER_DECISION, opportunity.intent)
        assertTrue(opportunity.requiresOwnerReview)
    }

    @Test
    fun sensitiveContentIsNeverAutomaticallyAnswered() {
        val analysis = SocialIntelligenceEngine.analyze("My bank sent me a one-time verification code: 123456.")

        assertTrue(analysis.sensitive)
        assertTrue(analysis.requiresOwnerReview)
        assertFalse(analysis.shouldRespond)
    }

    @Test
    fun completedMessagesAndUnansweredMessagesAreDistinguished() {
        val complete = SocialIntelligenceEngine.analyze(
            "What is the status?",
            listOf(
                SocialMessage("Rahul", "What is the status?"),
                SocialMessage("HOPE (AI assistant)", "Yash has not confirmed yet.")
            )
        )
        val unanswered = SocialIntelligenceEngine.analyze(
            "What is the status?",
            listOf(SocialMessage("Rahul", "What is the status?"))
        )

        assertEquals(SocialAnalysis.Intent.REPEATED_ANSWERED_QUESTION, complete.intent)
        assertFalse(complete.shouldRespond)
        assertTrue(unanswered.shouldRespond)
    }

    @Test
    fun repeatedGreetingAfterHopeRespondedIsSuppressed() {
        val analysis = SocialIntelligenceEngine.analyze(
            "Hi",
            listOf(SocialMessage("HOPE (AI assistant)", "Hi there."))
        )

        assertEquals(SocialAnalysis.Intent.REPEATED_ANSWERED_QUESTION, analysis.intent)
        assertFalse(analysis.shouldRespond)
    }

    @Test
    fun repeatedAnsweredQuestionsRecognizeEveryConfiguredPersona() {
        val analysis = SocialIntelligenceEngine.analyze(
            "What is the status?",
            listOf(
                SocialMessage("Rahul", "What is the status?"),
                SocialMessage("FRIDAY (AI persona)", "It is confirmed.")
            )
        )

        assertEquals(SocialAnalysis.Intent.REPEATED_ANSWERED_QUESTION, analysis.intent)
        assertFalse(analysis.shouldRespond)
    }

    @Test
    fun unnecessaryClosingsAndUninspectableMediaDoNotGetReplies() {
        val closing = SocialIntelligenceEngine.analyze("Thanks, bye!")
        val media = SocialIntelligenceEngine.analyze("Photo")
        val busy = SocialIntelligenceEngine.analyze("I'm busy, will message you later.")

        assertFalse(closing.shouldRespond)
        assertFalse(media.shouldRespond)
        assertEquals(SocialAnalysis.Intent.SENDER_BUSY, busy.intent)
        assertFalse(busy.shouldRespond)
        assertEquals(SocialAnalysis.Intent.UNNECESSARY_MESSAGE, media.intent)
    }

    @Test
    fun aStoppedConversationResumesOnlyForClearlyRelevantNewInformation() {
        assertFalse(SocialIntelligenceEngine.shouldResumeAfterStop("Okay, thanks."))
        assertTrue(SocialIntelligenceEngine.shouldResumeAfterStop("There's an interview opportunity with a deadline tomorrow."))
    }

    @Test
    fun localizedAcknowledgementsDoNotInventOwnerApproval() {
        val boundary = SocialIntelligenceEngine.analyze("Stop sending unnecessary messages.")
        val hindi = SocialIntelligenceEngine.holdingReply(boundary, "Hindi", "Yash se baat karna.")
        val noEscalation = SocialIntelligenceEngine.neutralBoundaryReply("English", "Stop contacting me.")

        assertTrue(hindi.orEmpty().contains("रुचि होने पर"))
        assertTrue(hindi.orEmpty().contains("Yash"))
        assertFalse(noEscalation.contains("Yash"))
    }
}
