package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaEngineTest {
    @Test
    fun exposesFourDistinctPersonasAndKeepsHopeAsDefault() {
        assertEquals(
            setOf(PersonaEngine.HOPE, PersonaEngine.FRIDAY, PersonaEngine.EDITH, PersonaEngine.ULTRON),
            PersonaEngine.profiles.map { it.id }.toSet()
        )
        assertEquals(PersonaEngine.HOPE, PersonaEngine.defaultProfile.id)
        assertTrue(PersonaEngine.profiles.all { it.defaultVoiceName in GeminiConstants.SUPPORTED_VOICES })
        assertEquals(
            PersonaEngine.profiles.size,
            PersonaEngine.profiles.map { it.defaultVoiceName }.toSet().size
        )
    }

    @Test
    fun personasHaveDistinctIdentitiesBehaviorsAndSharedPermissions() {
        val prompts = PersonaEngine.profiles.map { PersonaEngine.promptFor(it.id) }

        assertEquals(prompts.size, prompts.toSet().size)
        PersonaEngine.profiles.zip(prompts).forEach { (profile, prompt) ->
            assertTrue(prompt.contains("Identity: ${profile.identityName}"))
            assertTrue(prompt.contains(profile.identityDescription))
            assertTrue(prompt.contains("permissions"))
        }
        assertTrue(PersonaEngine.promptFor(PersonaEngine.HOPE).contains("You are HOPE"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.FRIDAY).contains("You are FRIDAY"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.EDITH).contains("You are E.D.I.T.H."))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("You are ULTRON"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.FRIDAY).contains("Notice emotional cues"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.EDITH).contains("verified facts"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("skeptical of weak reasoning"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.HOPE).contains("Sir or Boss only when it fits"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.FRIDAY).contains("occasional light wit"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.EDITH).contains("tactical assessment"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("you may tease or roast that specific choice"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("Keep it playful rather than degrading"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("If the user dislikes the teasing, stop immediately"))
        assertTrue(PersonaEngine.promptFor(PersonaEngine.ULTRON).contains("serious, sensitive, or distressed situations call for direct help"))
    }

    @Test
    fun legacySelectionsResolveToCompatiblePersonas() {
        assertEquals(PersonaEngine.HOPE, PersonaEngine.profileFor("HOPE Pro").id)
        assertEquals(PersonaEngine.FRIDAY, PersonaEngine.profileFor("Girlfriend Mode").id)
        assertEquals(PersonaEngine.EDITH, PersonaEngine.profileFor("Professional Mode").id)
        assertEquals(PersonaEngine.HOPE, PersonaEngine.profileFor("unrecognized value").id)
    }
}
