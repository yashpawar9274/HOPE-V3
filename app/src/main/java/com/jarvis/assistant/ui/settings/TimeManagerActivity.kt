package com.jarvis.assistant.ui.settings

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.calendar.CalendarManager
import com.jarvis.assistant.databinding.ActivityTimeManagerBinding
import com.jarvis.assistant.reminder.ReminderEntity
import com.jarvis.assistant.reminder.ReminderManager
import com.jarvis.assistant.time.AlarmManagerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class TimeManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTimeManagerBinding
    private val calendars by lazy { CalendarManager(this) }
    private val reminders by lazy { ReminderManager(this) }

    private val calendarPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionStatus()
        refreshCalendar()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTimeManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        setupActions()
        updatePermissionStatus()
        refreshCalendar()
        observeReminders()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updatePermissionStatus()
            refreshCalendar()
            val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (notificationsAllowed &&
                androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
            ) {
                lifecycleScope.launch { reminders.reschedulePending() }
            }
        }
    }

    private fun setupActions() {
        binding.btnCalendarPermission.setOnClickListener {
            calendarPermissionRequest.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        binding.btnOpenAlarms.setOnClickListener {
            showActionResult(AlarmManagerController.showAlarms(this))
        }
        binding.btnOpenTimer.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                val duration = hour * 3600 + minute * 60
                if (duration <= 0) {
                    Toast.makeText(this, "Choose a timer duration greater than zero.", Toast.LENGTH_LONG).show()
                } else {
                    showActionResult(
                        AlarmManagerController.setTimer(this, duration, "HOPE Timer")
                    )
                }
            }, 0, 30, true).show()
        }
        binding.btnAddReminder.setOnClickListener { showReminderTimePicker(null) }
        binding.btnNotificationSettings.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    private fun updatePermissionStatus() {
        val read = calendars.hasReadPermission()
        val write = calendars.hasWritePermission()
        binding.tvCalendarPermission.text = when {
            read && write -> "Calendar access: read and write enabled"
            read -> "Calendar access: read enabled • write not enabled"
            else -> "Calendar access: not granted"
        }
        binding.tvCalendarPermission.setTextColor(
            ContextCompat.getColor(this, if (read) com.jarvis.assistant.R.color.status_green else com.jarvis.assistant.R.color.text_subtle)
        )
        binding.btnCalendarPermission.text = if (read && write) "Calendar Access Granted" else "Grant Calendar Access"

        val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = hasNotificationPermission &&
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        binding.tvReminderPermission.text = when {
            notificationsEnabled ->
                "Notifications: allowed • reminder timing may be delayed by Android battery policies"
            !hasNotificationPermission ->
                "Notification permission is needed for reminder alerts"
            else ->
                "Notifications are disabled for HOPE in Android settings"
        }
    }

    private fun refreshCalendar() {
        if (!::binding.isInitialized) return
        if (!calendars.hasReadPermission()) {
            binding.tvCalendarEvents.text = "Grant calendar access to view events stored in calendars on this device."
            return
        }
        lifecycleScope.launch {
            try {
                val events = withContext(Dispatchers.IO) {
                    val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    calendars.events(start, start + 7L * 24 * 60 * 60 * 1000)
                }
                binding.tvCalendarEvents.text = if (events.isEmpty()) {
                    "No events found in the visible device calendars for today and the next 7 days."
                } else {
                    events.take(30).joinToString("\n\n") { event ->
                        "${event.title.ifBlank { "(No title)" }}\n${event.displayTime()}\n${event.calendarName}" +
                            (event.location?.takeIf(String::isNotBlank)?.let { "\n$it" } ?: "")
                    }
                }
            } catch (error: SecurityException) {
                binding.tvCalendarEvents.text = "Calendar access was denied by Android."
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Could not load calendar events.", error)
                binding.tvCalendarEvents.text = "Could not load calendar events: ${error.message ?: "unknown error"}"
            }
        }
    }

    private fun observeReminders() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                reminders.observePending().collect { list -> renderReminders(list) }
            }
        }
    }

    private fun renderReminders(items: List<ReminderEntity>) {
        binding.reminderList.removeAllViews()
        if (items.isEmpty()) {
            binding.reminderList.addView(label("No upcoming reminders.", 14, Color.LTGRAY))
            return
        }
        items.forEach { reminder ->
            val row = TextView(this).apply {
                text = "${reminder.text}\n${Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a", Locale.getDefault()))} • ${reminder.recurrence.lowercase()}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ContextCompat.getDrawable(this@TimeManagerActivity, com.jarvis.assistant.R.drawable.bg_jarvis_card)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
                setOnClickListener { showReminderActions(reminder) }
            }
            binding.reminderList.addView(row)
        }
    }

    private fun showReminderTimePicker(existing: ReminderEntity?) {
        val default = existing?.let { Instant.ofEpochMilli(it.triggerAtMillis).atZone(ZoneId.systemDefault()) }
        val seed = default?.toLocalDate() ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (selected <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Choose a future date and time.", Toast.LENGTH_LONG).show()
                    return@TimePickerDialog
                }
                showReminderDetails(existing, selected)
            }, default?.hour ?: 9, default?.minute ?: 0, false).show()
        }, seed.year, seed.monthValue - 1, seed.dayOfMonth).show()
    }

    private fun showReminderDetails(existing: ReminderEntity?, triggerAt: Long) {
        val input = EditText(this).apply {
            hint = "What should HOPE remind you about?"
            setText(existing?.text.orEmpty())
            setSingleLine(false)
        }
        val recurrence = arrayOf("One time", "Daily", "Weekly")
        val currentIndex = when (existing?.recurrence) {
            "DAILY" -> 1
            "WEEKLY" -> 2
            else -> 0
        }
        var selectedRecurrence = currentIndex
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New reminder" else "Edit reminder")
            .setView(content)
            .setSingleChoiceItems(recurrence, currentIndex) { _, which -> selectedRecurrence = which }
            .setPositiveButton(if (existing == null) "Save" else "Update") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(this, "Reminder text is required.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val repeat = arrayOf("NONE", "DAILY", "WEEKLY")[selectedRecurrence]
                lifecycleScope.launch {
                    try {
                        if (existing == null) {
                            reminders.create(text, triggerAt, repeat)
                        } else {
                            val zone = ZoneId.systemDefault()
                            reminders.update(
                                existing.copy(
                                    text = text,
                                    triggerAtMillis = triggerAt,
                                    localDateTime = Instant.ofEpochMilli(triggerAt).atZone(zone).toLocalDateTime().toString(),
                                    timeZoneId = zone.id,
                                    recurrence = repeat
                                )
                            )
                        }
                        Toast.makeText(this@TimeManagerActivity, "Reminder saved.", Toast.LENGTH_SHORT).show()
                        ensureNotificationPermission()
                    } catch (error: Exception) {
                        Toast.makeText(this@TimeManagerActivity, "Could not save reminder: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReminderActions(reminder: ReminderEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(reminder.text)
            .setItems(arrayOf("Mark completed", "Edit", "Delete")) { _, which ->
                lifecycleScope.launch {
                    try {
                        when (which) {
                            0 -> reminders.markCompleted(reminder.id)
                            1 -> showReminderTimePicker(reminder)
                            2 -> MaterialAlertDialogBuilder(this@TimeManagerActivity)
                                .setTitle("Delete reminder?")
                                .setMessage("This will remove this reminder.")
                                .setPositiveButton("Delete") { _, _ ->
                                    lifecycleScope.launch { reminders.delete(reminder.id) }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    } catch (error: Exception) {
                        Toast.makeText(this@TimeManagerActivity, "Reminder action failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showActionResult(result: com.jarvis.assistant.time.AlarmActionResult) {
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
    }

    private fun label(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TimeManagerActivity"
    }
}
