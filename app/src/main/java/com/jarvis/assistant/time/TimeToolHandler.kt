package com.jarvis.assistant.time

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.calendar.CalendarManager
import com.jarvis.assistant.reminder.ReminderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TimeToolHandler(context: Context) {
    private val appContext = context.applicationContext
    private val calendarManager = CalendarManager(appContext)
    private val reminderManager = ReminderManager(appContext)

    suspend fun execute(
        name: String,
        args: JSONObject,
        userTurnText: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "list_calendar_events" -> listEvents(args)
                "list_device_calendars" -> listCalendars()
                "create_calendar_event" -> createEvent(args, userTurnText)
                "update_calendar_event" -> updateEvent(args, userTurnText)
                "delete_calendar_event" -> deleteEvent(args, userTurnText)
                "create_reminder" -> createReminder(args)
                "list_reminders" -> listReminders()
                "complete_reminder" -> completeReminder(args)
                "manage_alarm" -> manageAlarm(args)
                else -> failure("Unsupported time-management action.")
            }
        } catch (error: SecurityException) {
            failure("Android denied calendar access. Open Settings > Time Manager and grant Calendar access.")
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Time tool $name failed.", error)
            failure(error.message ?: "Time-management action failed.")
        }
    }

    private suspend fun listEvents(args: JSONObject): JSONObject {
        if (!calendarManager.hasReadPermission()) {
            return failure("Calendar permission is missing. Open HOPE Settings > Time Manager and grant calendar read access.")
        }
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val (from, to) = when (args.optString("range").lowercase(Locale.ROOT)) {
            "today" -> today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() to
                today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            "search" -> {
                val start = parseTime(args.optString("start_at"))
                    ?: return failure("Search dates are unclear. Ask the user for a date or date range.")
                val end = parseTime(args.optString("end_at"))
                    ?: return failure("Search end date is unclear. Ask the user for a date range.")
                if (end <= start) return failure("Search range end must be after its start.")
                start to end
            }
            else -> now to today.plusDays(7).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }
        val events = calendarManager.events(from, to, args.optString("query").takeIf(String::isNotBlank))
        return JSONObject().apply {
            put("success", true)
            put("count", events.size)
            put("events", JSONArray().apply {
                events.forEach { event ->
                    put(JSONObject().apply {
                        put("event_id", event.id)
                        put("calendar_id", event.calendarId)
                        put("calendar", event.calendarName)
                        put("title", event.title)
                        put("start_at", Instant.ofEpochMilli(event.startAtMillis).toString())
                        put("end_at", Instant.ofEpochMilli(event.endAtMillis).toString())
                        put("location", event.location.orEmpty())
                        put("description", event.description.orEmpty())
                    })
                }
            })
            put("message", if (events.isEmpty()) "No matching events were found in the device calendars." else "Events were read from Android calendars.")
        }
    }

    private suspend fun createEvent(args: JSONObject, userTurnText: String): JSONObject {
        if (!calendarManager.hasWritePermission() || !calendarManager.hasReadPermission()) {
            return failure("Calendar read/write permission is required. Ask the user to grant it in HOPE Settings > Time Manager.")
        }
        val title = args.optString("title").trim()
        val start = parseTime(args.optString("start_at"))
            ?: return failure("Event start date and time are unclear. Ask the user to clarify before scheduling.")
        val end = parseTime(args.optString("end_at"))
            ?: return failure("Event end time or duration is missing. Ask the user to clarify before scheduling.")
        if (title.isBlank()) return failure("Event title is missing. Ask what the event is called.")
        if (end <= start) return failure("Event end must be after its start.")

        val existing = calendarManager.events(start - DAY_MS, end + DAY_MS, limit = 500)
        val duplicate = existing.firstOrNull {
            it.title.equals(title, true) && it.startAtMillis == start && it.endAtMillis == end
        }
        if (duplicate != null) {
            return JSONObject().apply {
                put("success", false)
                put("duplicate", true)
                put("event_id", duplicate.id)
                put("message", "An event with the same title and time already exists; no duplicate was created.")
            }
        }
        val writableCalendars = calendarManager.writableCalendars()
        if (!args.has("calendar_id") && writableCalendars.size > 1) {
            return JSONObject().apply {
                put("success", false)
                put("requires_calendar_selection", true)
                put("calendars", JSONArray().apply {
                    writableCalendars.forEach {
                        put(JSONObject().put("calendar_id", it.id).put("name", it.name))
                    }
                })
                put("message", "Multiple writable calendars are available. Ask the user which one to use; do not choose silently.")
            }
        }
        val conflicts = existing.filter {
            it.startAtMillis < end && it.endAtMillis > start
        }
        if (conflicts.isNotEmpty()) {
            val confirmationKey = "create|$title|$start|$end"
            if (!args.optBoolean("confirm_conflict", false)) {
                stageConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)
                return JSONObject().apply {
                    put("success", false)
                    put("requires_confirmation", true)
                    put("conflicts", JSONArray().apply {
                        conflicts.take(5).forEach {
                            put("${it.title} (${it.displayTime()})")
                        }
                    })
                    put("message", "This time conflicts with existing events. Explain the conflict and ask whether to schedule anyway.")
                }
            }
            if (!consumeConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)) {
                return failure("The user has not explicitly confirmed scheduling over this conflict in a later message. Ask first.")
            }
        }
        val eventId = calendarManager.createEvent(
            title = title,
            startAtMillis = start,
            endAtMillis = end,
            location = args.optString("location").takeIf(String::isNotBlank),
            description = args.optString("description").takeIf(String::isNotBlank),
            calendarId = args.optLong("calendar_id").takeIf { args.has("calendar_id") }
        )
        return JSONObject().apply {
            put("success", true)
            put("event_id", eventId)
            put("message", "Event was added to the selected writable Android calendar.")
        }
    }

    private suspend fun listCalendars(): JSONObject {
        if (!calendarManager.hasReadPermission()) {
            return failure("Calendar permission is missing. Open HOPE Settings > Time Manager and grant access.")
        }
        val calendars = calendarManager.writableCalendars()
        return JSONObject().apply {
            put("success", true)
            put("count", calendars.size)
            put("calendars", JSONArray().apply {
                calendars.forEach { calendar ->
                    put(JSONObject().apply {
                        put("calendar_id", calendar.id)
                        put("name", calendar.name)
                        put("account", calendar.accountName)
                        put("writable", calendar.writable)
                    })
                }
            })
        }
    }

    private suspend fun updateEvent(args: JSONObject, userTurnText: String): JSONObject {
        if (!calendarManager.hasWritePermission() || !calendarManager.hasReadPermission()) {
            return failure("Calendar read/write permission is required. Grant it in HOPE Settings > Time Manager.")
        }
        val id = args.optLong("event_id", -1L)
        if (id <= 0L) return failure("A valid event ID is required to update an event.")
        val old = calendarManager.findEvent(id) ?: return failure("The requested event could not be found.")
        if (calendarManager.isRecurringEvent(id)) {
            val confirmationKey = "series_update|$id"
            if (!args.optBoolean("confirmed_series", false)) {
                stageConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)
                return JSONObject().apply {
                    put("success", false)
                    put("requires_confirmation", true)
                    put("event_id", id)
                    put("message", "This is a recurring event. Tell the user the change will affect the entire series and ask for confirmation.")
                }
            }
            if (!consumeConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)) {
                return failure("The user has not confirmed updating the entire recurring series in a later message.")
            }
        }
        if (args.has("start_at") && parseTime(args.optString("start_at")) == null) {
            return failure("The replacement start date or time is unclear.")
        }
        if (args.has("end_at") && parseTime(args.optString("end_at")) == null) {
            return failure("The replacement end date or time is unclear.")
        }
        val title = args.optString("title", old.title).ifBlank { old.title }
        val start = parseTime(args.optString("start_at")) ?: old.startAtMillis
        val end = parseTime(args.optString("end_at")) ?: old.endAtMillis
        val conflicts = calendarManager.conflicts(start, end, exceptEventId = id)
        if (conflicts.isNotEmpty()) {
            val confirmationKey = "update|$id|$start|$end"
            if (!args.optBoolean("confirm_conflict", false)) {
                stageConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)
                return JSONObject().apply {
                    put("success", false)
                    put("requires_confirmation", true)
                    put("conflicts", JSONArray().apply { conflicts.take(5).forEach { put("${it.title} (${it.displayTime()})") } })
                    put("message", "The updated time conflicts with another event. Explain the conflict and ask whether to continue.")
                }
            }
            if (!consumeConfirmation(CONFLICT_CONFIRMATIONS, confirmationKey, userTurnText)) {
                return failure("The user has not explicitly confirmed this conflicting change in a later message. Ask first.")
            }
        }
        val updated = calendarManager.updateEvent(
            id,
            title,
            start,
            end,
            args.optString("location").takeIf(String::isNotBlank) ?: old.location,
            args.optString("description").takeIf(String::isNotBlank) ?: old.description
        )
        return JSONObject().apply {
            put("success", updated)
            put("message", if (updated) "Calendar event updated." else "Android did not update the calendar event.")
        }
    }

    private suspend fun deleteEvent(args: JSONObject, userTurnText: String): JSONObject {
        if (!calendarManager.hasWritePermission() || !calendarManager.hasReadPermission()) {
            return failure("Calendar read/write permission is required. Grant it in HOPE Settings > Time Manager.")
        }
        val id = args.optLong("event_id", -1L)
        if (id <= 0L) return failure("A valid event ID is required. Search the calendar first.")
        val event = calendarManager.findEvent(id) ?: return failure("The requested event could not be found.")
        val recurring = calendarManager.isRecurringEvent(id)
        if (!args.optBoolean("confirmed", false)) {
            stageConfirmation(DELETE_CONFIRMATIONS, id.toString(), userTurnText)
            return JSONObject().apply {
                put("success", false)
                put("requires_confirmation", true)
                put("event_id", id)
                put("event_title", event.title)
                put("event_time", event.displayTime())
                put("recurring_series", recurring)
                put("message", if (recurring) {
                    "Do not delete yet. Tell the user this removes the entire recurring series, then ask for explicit confirmation."
                } else {
                    "Do not delete yet. Tell the user the event title and time, then ask for explicit confirmation."
                })
            }
        }
        if (!consumeConfirmation(DELETE_CONFIRMATIONS, id.toString(), userTurnText)) {
            return failure("No explicit confirmation was received after the event details were shown. Ask the user again; nothing was deleted.")
        }
        val deleted = calendarManager.deleteEvent(id, explicitlyConfirmed = true)
        return JSONObject().apply {
            put("success", deleted)
            put("message", if (deleted) "The confirmed calendar event was deleted." else "Android did not delete the calendar event.")
        }
    }

    private suspend fun createReminder(args: JSONObject): JSONObject {
        val text = args.optString("text").trim()
        if (text.isBlank()) return failure("Reminder text is missing. Ask what HOPE should remind the user about.")
        val scheduled = TimeCommandParser.parseDateTime(args.optString("date_time"))
            ?: return failure("Reminder date or time is unclear. Ask the user to clarify.")
        if (!scheduled.toInstant().isAfter(Instant.now())) {
            return failure("Reminder time must be in the future. Ask the user to provide a future time.")
        }
        val recurrence = args.optString("recurrence", "NONE").uppercase(Locale.ROOT)
        val reminder = reminderManager.create(
            text = text,
            triggerAtMillis = scheduled.toInstant().toEpochMilli(),
            recurrence = recurrence,
            timeZoneId = scheduled.zone.id
        )
        val runtimeNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationAllowed = runtimeNotificationPermission &&
            androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        return JSONObject().apply {
            put("success", true)
            put("reminder_id", reminder.id)
            put("notification_permission_granted", notificationAllowed)
            put("message", if (notificationAllowed) {
                "Reminder saved and scheduled. WorkManager may deliver it later than the exact target time."
            } else {
                "Reminder saved, but notifications are not enabled for HOPE; the alert may not be visible. Ask the user to enable them in Time Manager."
            })
        }
    }

    private suspend fun listReminders(): JSONObject {
        val items = reminderManager.listPending()
        return JSONObject().apply {
            put("success", true)
            put("count", items.size)
            put("reminders", JSONArray().apply {
                items.take(50).forEach {
                    put(JSONObject().apply {
                        put("reminder_id", it.id)
                        put("text", it.text)
                        put("date_time", Instant.ofEpochMilli(it.triggerAtMillis).toString())
                        put("recurrence", it.recurrence)
                    })
                }
            })
        }
    }

    private suspend fun completeReminder(args: JSONObject): JSONObject {
        val id = args.optString("reminder_id")
        if (id.isBlank()) return failure("Reminder ID is required.")
        val updated = reminderManager.markCompleted(id)
        return JSONObject().put("success", updated).put(
            "message",
            if (updated) "Reminder marked completed." else "No pending reminder with that ID was found."
        )
    }

    private fun manageAlarm(args: JSONObject): JSONObject {
        val result = when (args.optString("action").lowercase(Locale.ROOT)) {
            "set" -> AlarmManagerController.setAlarm(
                appContext,
                args.optInt("hour", -1),
                args.optInt("minute", -1),
                args.optString("label")
            )
            "timer" -> {
                val secondsValue = args.optLong("duration_seconds", -1L)
                val minutesValue = args.optLong("duration_minutes", -1L)
                val seconds = when {
                    secondsValue in 1..604_800 -> secondsValue.toInt()
                    secondsValue > 0 -> return failure("Timer duration cannot exceed 7 days.")
                    minutesValue in 1..10_080 -> (minutesValue * 60).toInt()
                    else -> return failure("A valid timer duration between 1 second and 7 days is required.")
                }
                AlarmManagerController.setTimer(appContext, seconds, args.optString("label"))
            }
            "set_recurring" -> {
                val days = args.optJSONArray("weekdays")
                    ?: return failure("Weekdays are required for a recurring alarm.")
                val weekdays = (0 until days.length()).map { days.optInt(it, -1) }
                if (weekdays.isEmpty()) return failure("At least one weekday is required.")
                AlarmManagerController.setAlarm(
                    appContext,
                    args.optInt("hour", -1),
                    args.optInt("minute", -1),
                    args.optString("label"),
                    weekdays.toIntArray()
                )
            }
            "show" -> AlarmManagerController.showAlarms(appContext)
            else -> return failure("Alarm action must be set, set_recurring, timer, or show.")
        }
        return JSONObject().put("success", result.success).put("message", result.message)
    }

    private fun parseTime(value: String): Long? =
        TimeCommandParser.parseInstantMillis(value, ZoneId.systemDefault())

    private fun failure(message: String) = JSONObject().put("success", false).put("message", message)

    private fun stageConfirmation(
        pending: ConcurrentHashMap<String, PendingConfirmation>,
        key: String,
        userTurnText: String
    ) {
        if (userTurnText.isBlank()) return
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAtMillis > CONFIRMATION_WINDOW_MS }
        pending[key] = PendingConfirmation(userTurnText, now)
    }

    private fun consumeConfirmation(
        pending: ConcurrentHashMap<String, PendingConfirmation>,
        key: String,
        userTurnText: String
    ): Boolean {
        val confirmation = pending[key] ?: return false
        if (System.currentTimeMillis() - confirmation.createdAtMillis > CONFIRMATION_WINDOW_MS) {
            pending.remove(key)
            return false
        }
        if (userTurnText.isBlank() ||
            userTurnText.trim().lowercase(Locale.ROOT) == confirmation.requestText.trim().lowercase(Locale.ROOT)
        ) return false
        if (!isExplicitAffirmative(userTurnText)) {
            pending.remove(key)
            return false
        }
        return pending.remove(key, confirmation)
    }

    private fun isExplicitAffirmative(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        if (listOf("no", "nope", "don't", "dont", "cancel", "nahi", "nahin", "mat")
                .any { normalized == it || normalized.startsWith("$it ") || normalized.contains(" $it ") }
        ) return false
        return normalized in setOf(
            "yes", "yes please", "yeah", "yep", "sure", "ok", "okay", "confirm", "confirmed",
            "go ahead", "delete it", "please delete", "haan", "han", "ha", "theek hai",
            "kar do", "hata do", "haan delete kar do"
        ) || normalized.matches(Regex("^(yes|yeah|yep|sure|okay|ok|haan|han)([, ].*)?$")) ||
            normalized.startsWith("please delete ")
    }

    private data class PendingConfirmation(val requestText: String, val createdAtMillis: Long)

    private companion object {
        const val TAG = "TimeToolHandler"
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val CONFIRMATION_WINDOW_MS = 2L * 60L * 1000L
        val DELETE_CONFIRMATIONS = ConcurrentHashMap<String, PendingConfirmation>()
        val CONFLICT_CONFIRMATIONS = ConcurrentHashMap<String, PendingConfirmation>()
    }
}
