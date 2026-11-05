package com.jarvis.assistant.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Lets HOPE run screen-based tasks (open app, tap, type, YouTube, WhatsApp...) when the display is off.
 *
 * It turns the screen on with a wake lock and keeps it on while the task runs. It can NOT unlock a
 * phone that is protected by a PIN, pattern, password or fingerprint - Android does not allow that
 * and HOPE never stores unlock credentials. In that case [Result.LOCKED] is returned so HOPE can ask
 * the owner to unlock once.
 */
object ScreenWaker {
    private const val TAG = "ScreenWaker"
    private const val HOLD_MS = 2 * 60_000L
    private const val SCREEN_ON_WAIT_MS = 2_000L

    enum class Result { SCREEN_ON, LOCKED, FAILED }

    /** Tools that need the display. Calls, volume, torch, location etc. work with the screen off. */
    val SCREEN_TOOLS: Set<String> = setOf(
        "open_app", "open_website", "search_in_chrome", "search_and_play_youtube", "search_youtube",
        "youtube_accessibility_action", "send_whatsapp_message", "whatsapp_call",
        "click_ui_element", "type_text", "scroll_screen", "press_key", "inspect_screen", "take_screenshot",
        "open_system_settings", "prepare_upi_payment"
    )

    private var lock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Synchronized
    fun prepareForTask(context: Context): Result {
        val app = context.applicationContext
        return try {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val current = lock
            if (current != null && current.isHeld && power.isInteractive) {
                current.acquire(HOLD_MS) // extend while the task is still running
            } else {
                if (current?.isHeld == true) current.release()
                // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP is deprecated for activities but is the
                // supported way for a background service to switch the display on.
                val created = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "${app.packageName}:HopeTask"
                )
                created.setReferenceCounted(false)
                created.acquire(HOLD_MS)
                lock = created
            }
            var waited = 0L
            while (!power.isInteractive && waited < SCREEN_ON_WAIT_MS) {
                Thread.sleep(100)
                waited += 100
            }
            when {
                !power.isInteractive -> Result.FAILED
                keyguard.isKeyguardLocked -> Result.LOCKED
                else -> Result.SCREEN_ON
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not turn the screen on for a task.", error)
            Result.FAILED
        }
    }

    /** Lets the screen go back to sleep normally once the task is over. */
    @Synchronized
    fun release() {
        try {
            lock?.let { if (it.isHeld) it.release() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the task wake lock.", error)
        }
        lock = null
    }
}
