package com.jarvis.assistant.network

import org.json.JSONArray
import org.json.JSONObject

object TimeToolDeclarations {
    fun addTo(declarations: JSONArray) {
        declarations.put(tool(
            "list_device_calendars",
            "List visible writable calendars on the device when the user asks to choose a calendar or names a particular calendar. Do not assume a Google account. Calendar names and account details are private; return only what is needed.",
            emptyMap(),
            emptyArray()
        ))
        declarations.put(tool(
            "list_calendar_events",
            "Only when the user asks about their schedule, list events from Android calendars. Use range=today for today's schedule, upcoming for the next 7 days, or search for a title/date query. Calendar contents are private: return only requested event details. If permission is missing, tell them to open HOPE Settings > Time Manager.",
            mapOf(
                "range" to string("today, upcoming, or search", listOf("today", "upcoming", "search")),
                "query" to string("Optional case-insensitive title fragment"),
                "start_at" to string("Optional ISO-8601 date-time with UTC offset for search"),
                "end_at" to string("Optional ISO-8601 date-time with UTC offset for search")
            ),
            arrayOf("range")
        ))
        declarations.put(tool(
            "create_calendar_event",
            "Understand the user's English, Hindi, or Hinglish request, but create an event only when its title and date/time are clear. Ask a clarification instead of guessing missing title, date, start or end/duration. Use ISO-8601 date-times with the device's current UTC offset. Check time conflicts first; if a conflict is reported, tell the user and obtain explicit confirmation before retrying with confirm_conflict=true. Android calendars vary by device; only report success after Calendar Provider insertion succeeds.",
            mapOf(
                "title" to string("Event title"),
                "start_at" to string("ISO-8601 local date-time including UTC offset"),
                "end_at" to string("ISO-8601 local date-time including UTC offset"),
                "location" to string("Optional location"),
                "description" to string("Optional event description"),
                "calendar_id" to integer("Optional visible writable device calendar ID"),
                "confirm_conflict" to boolean("True only after the user explicitly confirms a reported conflict")
            ),
            arrayOf("title", "start_at", "end_at")
        ))
        declarations.put(tool(
            "update_calendar_event",
            "Update a calendar event only when its event_id is known from a prior calendar search or result. Preserve unspecified fields. Check conflicts and ask the user before confirm_conflict=true. If it is recurring, explain the change affects the full series and wait for explicit confirmation before confirmed_series=true.",
            mapOf(
                "event_id" to integer("Android Calendar Provider event ID"),
                "title" to string("Optional replacement title"),
                "start_at" to string("Optional replacement ISO-8601 time"),
                "end_at" to string("Optional replacement ISO-8601 time"),
                "location" to string("Optional replacement location"),
                "description" to string("Optional replacement description"),
                "confirm_conflict" to boolean("True only after user confirms a reported conflict"),
                "confirmed_series" to boolean("True only after user explicitly confirms that changes apply to the entire recurring series")
            ),
            arrayOf("event_id")
        ))
        declarations.put(tool(
            "delete_calendar_event",
            "Deleting a calendar event is destructive. First call with confirmed=false, tell the user the returned event title and time, and ask them to explicitly confirm. Only call again with the same event_id and confirmed=true after a clear affirmative reply. Never infer confirmation from the original delete request.",
            mapOf(
                "event_id" to integer("Android calendar event ID"),
                "confirmed" to boolean("Set true only after explicit user confirmation in a later turn")
            ),
            arrayOf("event_id", "confirmed")
        ))
        declarations.put(tool(
            "create_reminder",
            "Understand the user's English, Hindi, or Hinglish request. Save a HOPE reminder using the existing device timezone. Require reminder text and an unambiguous future date/time; ask if unclear. WorkManager reminders are durable but inexact and can be delayed by Android battery policies. Recurrence supports NONE, DAILY, WEEKLY.",
            mapOf(
                "text" to string("Reminder message"),
                "date_time" to string("ISO-8601 date-time; use current device UTC offset"),
                "recurrence" to string("NONE, DAILY, or WEEKLY", listOf("NONE", "DAILY", "WEEKLY"))
            ),
            arrayOf("text", "date_time")
        ))
        declarations.put(tool(
            "list_reminders",
            "List the user's pending HOPE reminders only when requested.",
            emptyMap(),
            emptyArray()
        ))
        declarations.put(tool(
            "complete_reminder",
            "Mark a HOPE reminder complete when the user explicitly asks. Do not delete calendar events with this tool.",
            mapOf("reminder_id" to string("ID returned by list_reminders or reminder creation")),
            arrayOf("reminder_id")
        ))
        declarations.put(tool(
            "manage_alarm",
            "Use the installed Android clock app for user-requested alarms and timers. Report only if HOPE successfully submitted the supported intent. Recurring alarms are supported only if the clock app accepts them. Actions: set, set_recurring, timer, show. Weekdays use Android Calendar numbering: Sunday=1 through Saturday=7.",
            mapOf(
                "action" to string("set, set_recurring, timer, or show", listOf("set", "set_recurring", "timer", "show")),
                "hour" to integer("0-23 local hour for alarm actions"),
                "minute" to integer("0-59 for alarm actions"),
                "label" to string("Optional alarm or timer label"),
                "duration_seconds" to integer("Timer duration in seconds"),
                "duration_minutes" to integer("Timer duration in minutes"),
                "weekdays" to integerArray("For set_recurring, selected weekdays numbered Sunday=1 through Saturday=7")
            ),
            arrayOf("action")
        ))
    }

    private fun tool(
        name: String,
        description: String,
        fields: Map<String, JSONObject>,
        required: Array<String>
    ) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                fields.forEach { (key, value) -> put(key, value) }
            })
            if (required.isNotEmpty()) put("required", JSONArray(required.asList()))
        })
    }

    private fun string(description: String, choices: List<String>? = null) =
        JSONObject().apply {
            put("type", "STRING")
            put("description", description)
            choices?.let { put("enum", JSONArray(it)) }
        }

    private fun integer(description: String) = JSONObject()
        .put("type", "INTEGER")
        .put("description", description)

    private fun boolean(description: String) = JSONObject()
        .put("type", "BOOLEAN")
        .put("description", description)

    private fun integerArray(description: String) = JSONObject()
        .put("type", "ARRAY")
        .put("description", description)
        .put("items", JSONObject().put("type", "INTEGER"))
}
