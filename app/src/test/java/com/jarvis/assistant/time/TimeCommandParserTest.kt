package com.jarvis.assistant.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TimeCommandParserTest {
    @Test
    fun parsesOffsetDateTimeIntoRequestedZone() {
        val result = TimeCommandParser.parseDateTime(
            "2025-01-15T10:00:00+05:30",
            ZoneId.of("UTC")
        )

        assertEquals(Instant.parse("2025-01-15T04:30:00Z"), result?.toInstant())
    }

    @Test
    fun rejectsLocalTimesInDaylightSavingGapOrOverlap() {
        val zone = ZoneId.of("America/New_York")

        assertNull(TimeCommandParser.parseDateTime("2025-03-09T02:30:00", zone))
        assertNull(TimeCommandParser.parseDateTime("2025-11-02T01:30:00", zone))
        assertEquals(
            LocalDateTime.of(2025, 3, 9, 3, 30),
            TimeCommandParser.parseDateTime("2025-03-09T02:30:00-05:00", zone)?.toLocalDateTime()
        )
    }

    @Test
    fun parsesTimerDurationInEnglishAndHindi() {
        assertEquals(30L, TimeCommandParser.parseTimerDurationMinutes("half an hour"))
        assertEquals(120L, TimeCommandParser.parseTimerDurationMinutes("2 ghante"))
        assertNull(TimeCommandParser.parseTimerDurationMinutes("zero minutes"))
    }

    @Test
    fun dailyRecurrenceKeepsLocalTimeAcrossSpringDstTransition() {
        val zone = ZoneId.of("America/New_York")
        val result = TimeCommandParser.nextOccurrence(
            LocalDateTime.of(2025, 3, 8, 2, 30),
            "DAILY",
            Instant.parse("2025-03-08T15:00:00Z"),
            zone
        )

        assertEquals(LocalDateTime.of(2025, 3, 9, 3, 30), result?.toLocalDateTime())
        assertTrue(result!!.toInstant().isAfter(Instant.parse("2025-03-08T15:00:00Z")))
    }
}
