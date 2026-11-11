package com.jarvis.assistant.time

import java.time.Duration
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

object TimeCommandParser {
    fun parseDateTime(value: String, zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val clean = value.trim()
        if (clean.isEmpty()) return null
        try {
            return OffsetDateTime.parse(clean).atZoneSameInstant(zoneId)
        } catch (_: DateTimeParseException) {
        }
        try {
            return ZonedDateTime.parse(clean).withZoneSameInstant(zoneId)
        } catch (_: DateTimeParseException) {
        }
        return try {
            val local = LocalDateTime.parse(clean)
            val offsets = zoneId.rules.getValidOffsets(local)
            if (offsets.size != 1) null else ZonedDateTime.ofStrict(local, offsets.single(), zoneId)
        } catch (_: DateTimeException) {
            null
        }
    }

    fun parseInstantMillis(value: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? =
        parseDateTime(value, zoneId)?.toInstant()?.toEpochMilli()

    fun parseTimerDurationMinutes(value: String): Long? {
        val clean = value.lowercase(Locale.ROOT).trim()
        if (clean.contains("aadha ghanta") || clean.contains("half an hour")) return 30
        val match = Regex("""(\d+)\s*(minutes?|mins?|m|ghante?|hours?|hrs?|h)""")
            .find(clean) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "h", "hr", "hrs", "hour", "hours", "ghanta", "ghante" -> amount * 60
            else -> amount
        }.takeIf { it > 0 && it <= Duration.ofDays(7).toMinutes() }
    }

    fun nextOccurrence(
        localTime: LocalDateTime,
        recurrence: String,
        now: Instant,
        zoneId: ZoneId
    ): ZonedDateTime? {
        val current = now.atZone(zoneId)
        var candidate = localTime.atZone(zoneId)
        val kind = recurrence.uppercase(Locale.ROOT)
        if (kind !in setOf("NONE", "DAILY", "WEEKLY")) return null
        if (candidate.toInstant().isAfter(now)) return candidate
        if (kind == "NONE") return null

        val daysBehind = ChronoUnit.DAYS.between(candidate.toLocalDate(), current.toLocalDate())
        if (kind == "DAILY") {
            candidate = candidate.plusDays((daysBehind + 1).coerceAtLeast(1))
            while (!candidate.toInstant().isAfter(now)) candidate = candidate.plusDays(1)
        } else {
            val weeksBehind = ((daysBehind + 6) / 7).coerceAtLeast(1)
            candidate = candidate.plusWeeks(weeksBehind)
            while (!candidate.toInstant().isAfter(now)) candidate = candidate.plusWeeks(1)
        }
        return candidate.takeIf { it.toInstant().isAfter(current.toInstant()) }
    }
}
