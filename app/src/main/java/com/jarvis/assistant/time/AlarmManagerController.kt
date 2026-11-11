package com.jarvis.assistant.time

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.provider.AlarmClock

data class AlarmActionResult(val success: Boolean, val message: String)

object AlarmManagerController {
    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String,
        weekdays: IntArray? = null
    ): AlarmActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return AlarmActionResult(false, "Alarm time is invalid.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (weekdays != null) {
                if (weekdays.isEmpty() || weekdays.any { day -> day !in 1..7 }) {
                    return AlarmActionResult(false, "Recurring alarm weekdays are invalid.")
                }
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(weekdays.distinct().toList()))
            }
        }
        return submit(context, intent, "Alarm request submitted to the clock app.")
    }

    fun showAlarms(context: Context): AlarmActionResult =
        submit(
            context,
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            "Alarm manager opened."
        )

    fun setTimer(context: Context, durationSeconds: Int, label: String): AlarmActionResult {
        if (durationSeconds !in 1..604_800) {
            return AlarmActionResult(false, "Timer duration must be between 1 second and 7 days.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return submit(context, intent, "Timer request submitted to the clock app.")
    }

    private fun submit(context: Context, intent: Intent, successMessage: String): AlarmActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handler = intent.resolveActivity(context.packageManager)
            ?: return AlarmActionResult(false, "No installed clock app supports this alarm action.")
        intent.component = ComponentName(handler.packageName, handler.className)
        return try {
            context.startActivity(intent)
            AlarmActionResult(true, successMessage)
        } catch (error: Exception) {
            AlarmActionResult(false, "The clock app could not accept this request: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
