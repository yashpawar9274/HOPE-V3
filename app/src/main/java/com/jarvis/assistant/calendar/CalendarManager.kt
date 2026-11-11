package com.jarvis.assistant.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val accountName: String,
    val writable: Boolean
)

data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val calendarName: String,
    val title: String,
    val description: String?,
    val location: String?,
    val startAtMillis: Long,
    val endAtMillis: Long
) {
    fun displayTime(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a")
        val start = Instant.ofEpochMilli(startAtMillis).atZone(zoneId)
        val end = Instant.ofEpochMilli(endAtMillis).atZone(zoneId)
        return "${start.format(formatter)} – ${end.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))}"
    }
}

class CalendarManager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        requireReadPermission()
        val result = mutableListOf<DeviceCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val accessIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                result += DeviceCalendar(
                    cursor.getLong(idIndex),
                    cursor.getString(nameIndex).orEmpty(),
                    cursor.getString(accountIndex).orEmpty(),
                    cursor.getInt(accessIndex) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                )
            }
        }
        result
    }

    suspend fun events(
        fromMillis: Long,
        toMillis: Long,
        query: String? = null,
        limit: Int = 100
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        requireReadPermission()
        require(toMillis > fromMillis) { "Calendar range end must be after its start." }
        val rangeUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val cleanQuery = query?.trim()?.takeIf(String::isNotEmpty)
        val selection = if (cleanQuery != null) {
            "${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'"
        } else null
        val args = cleanQuery?.let {
            arrayOf("%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%")
        }
        val result = mutableListOf<CalendarEvent>()
        resolver.query(
            rangeUri,
            projection,
            selection,
            args,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val eventId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calendarId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val title = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val description = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val location = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val begin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val end = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val calendarName = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext() && result.size < limit.coerceIn(1, 500)) {
                result += CalendarEvent(
                    id = cursor.getLong(eventId),
                    calendarId = cursor.getLong(calendarId),
                    calendarName = cursor.getString(calendarName).orEmpty(),
                    title = cursor.getString(title).orEmpty(),
                    description = cursor.getString(description),
                    location = cursor.getString(location),
                    startAtMillis = cursor.getLong(begin),
                    endAtMillis = cursor.getLong(end)
                )
            }
        }
        result
    }

    suspend fun findEvent(id: Long): CalendarEvent? {
        val event = withContext(Dispatchers.IO) {
            requireReadPermission()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            resolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val calendarId = cursor.getLong(1)
                val calendarName = resolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                    "${CalendarContract.Calendars._ID}=?",
                    arrayOf(calendarId.toString()),
                    null
                )?.use { calendarCursor ->
                    if (calendarCursor.moveToFirst()) calendarCursor.getString(0).orEmpty() else ""
                }.orEmpty()
                CalendarEvent(
                    id = cursor.getLong(0),
                    calendarId = calendarId,
                    calendarName = calendarName,
                    title = cursor.getString(2).orEmpty(),
                    description = cursor.getString(3),
                    location = cursor.getString(4),
                    startAtMillis = cursor.getLong(5),
                    endAtMillis = cursor.getLong(6)
                )
            }
        }
        if (event != null && isRecurringEvent(id)) {
            val now = System.currentTimeMillis()
            return events(now, now + 366L * DAY_MS, limit = 500)
                .firstOrNull { it.id == id } ?: event
        }
        return event
    }

    suspend fun isRecurringEvent(id: Long): Boolean = withContext(Dispatchers.IO) {
        requireReadPermission()
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() &&
                (!cursor.isNull(0) && cursor.getString(0).isNotBlank() ||
                    !cursor.isNull(1) && cursor.getString(1).isNotBlank())
        } ?: false
    }

    suspend fun conflicts(
        startAtMillis: Long,
        endAtMillis: Long,
        exceptEventId: Long? = null
    ): List<CalendarEvent> =
        events(startAtMillis - DAY_MS, endAtMillis + DAY_MS, limit = 500)
            .filter { it.id != exceptEventId && it.startAtMillis < endAtMillis && it.endAtMillis > startAtMillis }

    suspend fun createEvent(
        title: String,
        startAtMillis: Long,
        endAtMillis: Long,
        location: String? = null,
        description: String? = null,
        calendarId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        requireWritePermission()
        requireReadPermission()
        require(title.isNotBlank()) { "Event title is required." }
        require(endAtMillis > startAtMillis) { "Event end must be after its start." }
        val calendars = queryWritableCalendars()
        val selected = if (calendarId != null) {
            calendars.firstOrNull { it.id == calendarId }
                ?: throw IllegalArgumentException("The selected calendar is not writable or is unavailable.")
        } else {
            calendars.firstOrNull()
                ?: throw IllegalStateException("No writable calendar is available on this device.")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, selected.id)
            put(CalendarContract.Events.TITLE, title.trim())
            put(CalendarContract.Events.DTSTART, startAtMillis)
            put(CalendarContract.Events.DTEND, endAtMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location?.trim().orEmpty())
            put(CalendarContract.Events.DESCRIPTION, description?.trim().orEmpty())
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("Android Calendar Provider did not insert the event.")
        ContentUris.parseId(uri)
    }

    suspend fun updateEvent(
        eventId: Long,
        title: String,
        startAtMillis: Long,
        endAtMillis: Long,
        location: String? = null,
        description: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        requireWritePermission()
        requireReadPermission()
        require(title.isNotBlank()) { "Event title is required." }
        require(endAtMillis > startAtMillis) { "Event end must be after its start." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title.trim())
            put(CalendarContract.Events.DTSTART, startAtMillis)
            put(CalendarContract.Events.DTEND, endAtMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location?.trim().orEmpty())
            put(CalendarContract.Events.DESCRIPTION, description?.trim().orEmpty())
        }
        resolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            values,
            null,
            null
        ) > 0
    }

    suspend fun deleteEvent(eventId: Long, explicitlyConfirmed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            require(explicitlyConfirmed) { "Deleting a calendar event requires explicit confirmation." }
            requireWritePermission()
            resolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null
            ) > 0
        }

    suspend fun writableCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        requireReadPermission()
        queryWritableCalendars()
    }

    private fun queryWritableCalendars(): List<DeviceCalendar> {
        val result = mutableListOf<DeviceCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val accessIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                result += DeviceCalendar(
                    cursor.getLong(idIndex),
                    cursor.getString(nameIndex).orEmpty(),
                    cursor.getString(accountIndex).orEmpty(),
                    cursor.getInt(accessIndex) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                )
            }
        }
        return result
    }

    private fun requireReadPermission() {
        check(hasReadPermission()) { "Calendar read permission is not granted. Open Time Manager and grant Calendar access." }
    }

    private fun requireWritePermission() {
        check(hasWritePermission()) { "Calendar write permission is not granted. Open Time Manager and grant Calendar access." }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

typealias CalendarRepository = CalendarManager
