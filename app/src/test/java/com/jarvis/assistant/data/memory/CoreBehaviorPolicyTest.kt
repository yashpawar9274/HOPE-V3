package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBehaviorPolicyTest {
    @Test
    fun recognizesExplicitPermanentInstructionsAndScopes() {
        val global = CoreBehaviorPolicy.extract(
            "Never promise a meeting before checking my calendar."
        )
        val whatsapp = CoreBehaviorPolicy.extract(
            "From now on, never send a WhatsApp reply that promises a meeting."
        )
        val hinglish = CoreBehaviorPolicy.extract(
            "HOPE, mujhe baar-baar same question mat poochna."
        )

        assertNotNull(global)
        assertEquals(CoreBehaviorRule.SCOPE_GLOBAL, global?.scope)
        assertEquals("permanent_restrictions", global?.category)
        assertEquals(CoreBehaviorRule.SCOPE_WHATSAPP, whatsapp?.scope)
        assertEquals(CoreBehaviorRule.SCOPE_GLOBAL, hinglish?.scope)
    }

    @Test
    fun doesNotPromoteOneTimeRequestsAndFlagsVaguePermanentRules() {
        assertNull(CoreBehaviorPolicy.extract("Don't open the camera now."))
        assertNull(CoreBehaviorPolicy.extract("I don't want people repeating the same question at me."))
        assertTrue(CoreBehaviorPolicy.isExplicitButAmbiguous("Never do this again."))
        assertTrue(CoreBehaviorPolicy.isExplicitButAmbiguous("From now on behave this way."))
    }

    @Test
    fun detectsContradictoryInstructionsWithoutOverwritingExistingRule() {
        val candidate = CoreBehaviorPolicy.extract("Always share my location.")!!
        val existing = CoreBehaviorRule(
            instructionId = "existing",
            instructionText = "Never share my location.",
            normalizedRule = CoreBehaviorPolicy.canonicalize("Never share my location."),
            category = "privacy_preferences",
            scope = CoreBehaviorRule.SCOPE_GLOBAL,
            priority = 100,
            createdAt = 1,
            updatedAt = 1,
        )

        assertTrue(CoreBehaviorPolicy.conflicts(candidate, existing))
        val meetingInstruction = CoreBehaviorPolicy.extract(
            "Never promise a meeting before checking my calendar."
        )!!
        val contradictoryMeetingRule = existing.copy(
            instructionText = "Always promise meetings.",
            normalizedRule = CoreBehaviorPolicy.canonicalize("Always promise meetings."),
        )
        assertTrue(CoreBehaviorPolicy.conflicts(meetingInstruction, contradictoryMeetingRule))
        assertFalse(
            CoreBehaviorPolicy.conflicts(
                candidate.copy(scope = CoreBehaviorRule.SCOPE_WHATSAPP),
                existing.copy(scope = CoreBehaviorRule.SCOPE_VOICE),
            )
        )
    }

    @Test
    fun legacyAndOtherAccountRulesAreNotOwnedByTheActiveAccount() {
        val accountARule = CoreBehaviorRule(
            instructionId = "rule-a",
            instructionText = "Keep replies concise.",
            normalizedRule = "keep replies concise",
            category = "communication_preferences",
            scope = CoreBehaviorRule.SCOPE_GLOBAL,
            priority = 50,
            createdAt = 1,
            updatedAt = 1,
            ownerUid = "account-a",
        )
        val legacyRule = accountARule.copy(
            instructionId = "legacy",
            ownerUid = CoreBehaviorRule.LEGACY_UNASSIGNED_UID,
        )

        assertTrue(accountARule.isOwnedBy("account-a"))
        assertFalse(accountARule.isOwnedBy("account-b"))
        assertFalse(legacyRule.isOwnedBy("account-a"))
        assertFalse(legacyRule.isOwnedBy(CoreBehaviorRule.LEGACY_UNASSIGNED_UID))
    }
}
