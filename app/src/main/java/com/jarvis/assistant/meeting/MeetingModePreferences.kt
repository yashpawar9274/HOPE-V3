package com.jarvis.assistant.meeting

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.autoreply.SmartAutoReply
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** Stored settings, expiry and activity log of HOPE Meeting Mode. */
object MeetingModePreferences {
    private const val TAG = "MeetingModePrefs"
    private const val FILE = "hope_meeting_mode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_END_AT = "end_at"
    private const val KEY_SITUATION = "situation"
    private const val KEY_DURATION = "duration_minutes"
    private const val KEY_PROMPT = "custom_prompt"
    private const val KEY_ALLOW_STARRED = "allow_starred"
    private const val KEY_ALLOW_REPEATED = "allow_repeated"
    private const val KEY_SMART_BY_MEETING = "smart_enabled_by_meeting"
    private const val KEY_LOG = "activity_log"
    private const val MAX_LOG = 40
    private const val ALARM_REQUEST_CODE = 7421

    data class LogEntry(val time: Long, val title: String, val detail: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True while Meeting Mode is on. Switches itself off once the chosen time is over. */
    fun isActive(context: Context): Boolean {
        val p = prefs(context)
        val enabled = p.getBoolean(KEY_ENABLED, false)
        if (!enabled) return false
        val active = MeetingModeLogic.isActive(true, p.getLong(KEY_END_AT, 0L), System.currentTimeMillis())
        if (!active) deactivate(context)
        return active
    }

    fun situation(context: Context): MeetingModeLogic.Situation =
        MeetingModeLogic.Situation.fromName(prefs(context).getString(KEY_SITUATION, null))

    fun setSituation(context: Context, situation: MeetingModeLogic.Situation) {
        prefs(context).edit().putString(KEY_SITUATION, situation.name).apply()
    }

    /** Minutes chosen in the screen; 0 means "until I switch it off". */
    fun durationMinutes(context: Context): Int = prefs(context).getInt(KEY_DURATION, 60)

    fun setDurationMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_DURATION, minutes.coerceIn(0, 24 * 60)).apply()
    }

    fun endAt(context: Context): Long = prefs(context).getLong(KEY_END_AT, 0L)

    fun customPrompt(context: Context): String = prefs(context).getString(KEY_PROMPT, "").orEmpty()

    fun setCustomPrompt(context: Context, text: String) {
        prefs(context).edit().putString(KEY_PROMPT, MeetingModeLogic.cleanCustomPrompt(text)).apply()
    }

    fun allowStarred(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_STARRED, true)

    fun setAllowStarred(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_STARRED, value).apply()
    }

    fun allowRepeated(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_REPEATED, true)

    fun setAllowRepeated(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_REPEATED, value).apply()
    }

    fun untilText(context: Context): String {
        val end = endAt(context)
        if (end <= 0L || !prefs(context).getBoolean(KEY_ENABLED, false)) return ""
        return android.text.format.DateFormat.getTimeFormat(context).format(Date(end))
    }

    /**
     * Turns Meeting Mode on. It also switches HOPE Smart Auto-Reply on (if it was off) so HOPE can keep
     * chatting with the callers who reply to her SMS; [deactivate] restores the previous state.
     */
    fun activate(context: Context, situation: MeetingModeLogic.Situation, durationMinutes: Int) {
        val app = context.applicationContext
        val smartWasOn = SmartAutoReply.isEnabled(app)
        val end = if (durationMinutes > 0) System.currentTimeMillis() + durationMinutes * 60_000L else 0L
        val alreadyByMeeting = prefs(app).getBoolean(KEY_SMART_BY_MEETING, false)
        check(
            prefs(app).edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_SITUATION, situation.name)
                .putInt(KEY_DURATION, durationMinutes.coerceIn(0, 24 * 60))
                .putLong(KEY_END_AT, end)
                .putBoolean(KEY_SMART_BY_MEETING, alreadyByMeeting || !smartWasOn)
                .commit()
        ) { "Could not save the Meeting Mode setting." }
        if (!smartWasOn) SmartAutoReply.enable(app)
        scheduleEnd(app, end)
        addLog(app, "Meeting Mode on", "${situation.label}${if (end > 0) " until ${untilText(app)}" else " until you switch it off"}")
    }

    fun deactivate(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val wasActive = p.getBoolean(KEY_ENABLED, false)
        val smartByMeeting = p.getBoolean(KEY_SMART_BY_MEETING, false)
        p.edit().putBoolean(KEY_ENABLED, false).putLong(KEY_END_AT, 0L).putBoolean(KEY_SMART_BY_MEETING, false).apply()
        cancelEnd(app)
        if (smartByMeeting) {
            try {
                SmartAutoReply.disable(app)
            } catch (error: Exception) {
                Log.w(TAG, "Could not restore Smart Auto-Reply after Meeting Mode.", error)
            }
        }
        if (wasActive) addLog(app, "Meeting Mode off", "")
    }

    /** Text that tells Smart Auto-Reply about the owner's situation, or null when Meeting Mode is off. */
    fun replyContext(context: Context, ownerName: String): Pair<String, String>? {
        if (!isActive(context)) return null
        val app = context.applicationContext
        return MeetingModeLogic.situationContext(ownerName, situation(app), untilText(app), allowRepeated(app)) to
            MeetingModeLogic.ownerInstructionsBlock(customPrompt(app))
    }

    @Synchronized
    fun addLog(context: Context, title: String, detail: String) {
        try {
            val p = prefs(context)
            val old = JSONArray(p.getString(KEY_LOG, "[]") ?: "[]")
            val list = JSONArray()
            list.put(JSONObject().put("t", System.currentTimeMillis()).put("title", title).put("detail", detail))
            for (i in 0 until minOf(old.length(), MAX_LOG - 1)) list.put(old.getJSONObject(i))
            p.edit().putString(KEY_LOG, list.toString()).apply()
        } catch (error: Exception) {
            Log.w(TAG, "Could not save the Meeting Mode log.", error)
        }
    }

    fun log(context: Context): List<LogEntry> = try {
        val array = JSONArray(prefs(context).getString(KEY_LOG, "[]") ?: "[]")
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            LogEntry(o.optLong("t"), o.optString("title"), o.optString("detail"))
        }
    } catch (error: Exception) {
        emptyList()
    }

    fun clearLog(context: Context) {
        prefs(context).edit().putString(KEY_LOG, "[]").apply()
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, MeetingModeEndReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleEnd(context: Context, endAt: Long) {
        cancelEnd(context)
        if (endAt <= 0L) return
        try {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, alarmIntent(context))
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule the Meeting Mode end alarm; it will still expire on next use.", error)
        }
    }

    private fun cancelEnd(context: Context) {
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context))
        } catch (error: Exception) {
            Log.w(TAG, "Could not cancel the Meeting Mode end alarm.", error)
        }
    }
}
