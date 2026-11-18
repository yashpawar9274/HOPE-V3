package com.jarvis.assistant.autoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltronAutoDefensePolicyTest {
    @Test
    fun onlyACompleteHighConfidenceTargetedClassificationActivatesDefense() {
        val targeted = UltronAutoDefensePolicy.parseClassification(
            """{"targeted_abuse":true,"confidence":0.97,"quoted_or_reported":false,"playful_or_non_targeted":false}"""
        )

        assertTrue(UltronAutoDefensePolicy.shouldActivate(targeted))
        assertFalse(UltronAutoDefensePolicy.shouldActivate(targeted, threshold = 0.99))
        assertFalse(
            UltronAutoDefensePolicy.shouldActivate(
                targeted?.copy(quotedOrReported = true)
            )
        )
        assertFalse(
            UltronAutoDefensePolicy.shouldActivate(
                targeted?.copy(playfulOrNonTargeted = true)
            )
        )
        assertFalse(
            UltronAutoDefensePolicy.shouldActivate(
                targeted?.copy(targetedAbuse = false)
            )
        )
    }

    @Test
    fun malformedOrAmbiguousClassifierOutputFailsClosed() {
        assertNull(UltronAutoDefensePolicy.parseClassification("No abuse detected."))
        assertNull(
            UltronAutoDefensePolicy.parseClassification(
                """{"targeted_abuse":true,"confidence":1.3,"quoted_or_reported":false,"playful_or_non_targeted":false}"""
            )
        )
        assertNull(
            UltronAutoDefensePolicy.parseClassification(
                """{"targeted_abuse":true,"targeted_abuse":false,"confidence":0.99,"quoted_or_reported":false,"playful_or_non_targeted":false}"""
            )
        )
        assertFalse(
            UltronAutoDefensePolicy.shouldActivate(
                UltronAutoDefensePolicy.parseClassification(
                    """{"targeted_abuse":true,"confidence":0.89,"quoted_or_reported":false,"playful_or_non_targeted":false}"""
                )
            )
        )
    }

    @Test
    fun firstUltronReplyMustIdentifyThePersonaOnceAndStayBrief() {
        val reply = UltronAutoDefensePolicy.firstReplyWithIdentity("Gaali se point strong nahi hota.")

        assertTrue(reply.startsWith("Main ULTRON hoon."))
        assertTrue(reply.length <= UltronAutoDefensePolicy.MAX_REPLY_LENGTH)
        assertEquals(
            "Main ULTRON hoon. Baat karo.",
            UltronAutoDefensePolicy.firstReplyWithIdentity("Main ULTRON hoon. Baat karo.")
        )
    }

    @Test
    fun generatedDefenseRejectsThreatsAndDirectPersonalInsults() {
        assertFalse(UltronAutoDefensePolicy.isSafeDefenseReply("I'll find your address."))
        assertFalse(UltronAutoDefensePolicy.isSafeDefenseReply("You're an idiot."))
        assertFalse(UltronAutoDefensePolicy.isSafeDefenseReply("You're useless."))
        assertFalse(UltronAutoDefensePolicy.isSafeDefenseReply("You're ugly."))
        assertTrue(UltronAutoDefensePolicy.isSafeDefenseReply("Let's keep this respectful."))
    }
}
