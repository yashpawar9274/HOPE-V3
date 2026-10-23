package com.jarvis.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQuestionPolicyTest {
    @Test
    fun recognizesDirectCurrentLocationQuestions() {
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("Where am I right now?"))
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("Tell me my current location"))
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("What's my location?"))
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("Where am I right now?"))
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("What is my location?"))
        assertTrue(LocationQuestionPolicy.isCurrentLocationQuestion("Meri location kya hai?"))
        assertFalse(LocationQuestionPolicy.isCurrentLocationQuestion("What's the weather tomorrow?"))
    }

    @Test
    fun recognizesRequestsThatCouldBenefitFromLocality() {
        assertTrue(LocationQuestionPolicy.isLocationRelevant("Find a pharmacy near me"))
        assertTrue(LocationQuestionPolicy.isLocationRelevant("What is the local weather?"))
        assertTrue(LocationQuestionPolicy.isLocationRelevant("Meri location kya hai?"))
        assertFalse(LocationQuestionPolicy.isLocationRelevant("Explain how GPS works"))
    }

    @Test
    fun geminiContextIncludesOnlyLocalityAndSnapshotMetadata() {
        val snapshot = HopeLocation(
            latitude = 17.385,
            longitude = 78.4867,
            accuracyMeters = 80f,
            updatedAtMillis = 1_800_000_000_000L,
            locality = "Hyderabad"
        )

        val context = snapshot.sharedContext()

        assertTrue(context.contains("Hyderabad"))
        assertTrue(context.contains("Snapshot time"))
        assertFalse(context.contains("17.385"))
        assertFalse(context.contains("78.4867"))
    }

    @Test
    fun missingLocalityCannotProduceGeminiLocationContext() {
        val snapshot = HopeLocation(0.0, 0.0, 100f, 1_800_000_000_000L, null)

        assertTrue(snapshot.sharedContext().isEmpty())
    }
}
