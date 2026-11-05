package com.jarvis.assistant.meeting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.autoreply.NotificationIntelligenceStore
import com.jarvis.assistant.autoreply.SmartReplyLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles incoming SIM calls while Meeting Mode is on: declines the call right away (no ringing),
 * then texts the caller from the owner's own SIM and logs it. Callers who reply are answered by
 * Smart Auto-Reply with the meeting context.
 *
 * HOPE cannot speak or listen inside a cellular call - Android gives apps no access to call audio -
 * so the conversation continues over SMS / WhatsApp instead.
 */
object MeetingCallScreener {
    private const val TAG = "MeetingCallScreener"
    private val tracker = MeetingModeLogic.RecentCallTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun canSendSms(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** @return true when the call was declined by Meeting Mode and must not reach the normal call screen. */
    fun screenIfNeeded(context: Context, call: Call): Boolean {
        if (call.state != Call.STATE_RINGING) return false
        val app = context.applicationContext
        if (!MeetingModePreferences.isActive(app)) return false

        val number = call.details.handle
            ?.takeIf { it.scheme.equals("tel", ignoreCase = true) }
            ?.schemeSpecificPart
            .orEmpty()
        val key = MeetingModeLogic.normalizeNumber(number)
        val now = System.currentTimeMillis()
        val earlierCalls = if (key.isNotEmpty()) tracker.countWithin(key, MeetingModeLogic.REPEAT_WINDOW_MS, now) else 0
        if (key.isNotEmpty()) tracker.record(key, now)

        val (contactName, starred) = lookupContact(app, number)
        val decision = MeetingModeLogic.decide(
            active = true,
            isStarred = starred,
            allowStarred = MeetingModePreferences.allowStarred(app),
            allowRepeated = MeetingModePreferences.allowRepeated(app),
            earlierCallsInWindow = earlierCalls
        )
        val who = contactName ?: number.takeIf(String::isNotBlank) ?: "Unknown caller"
        if (decision != MeetingModeLogic.Decision.SCREEN) {
            val why = if (decision == MeetingModeLogic.Decision.RING_THROUGH_STARRED) "starred contact" else "repeated call"
            MeetingModePreferences.addLog(app, "Rang through", "$who ($why)")
            return false
        }
        return try {
            call.reject(false, null)
            MeetingModePreferences.addLog(app, "Call declined", who)
            scope.launch { textCaller(app, number, contactName, who) }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not decline the call for Meeting Mode; letting it ring normally.", error)
            false
        }
    }

    private suspend fun textCaller(app: Context, number: String, contactName: String?, who: String) {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:MeetingSms")
        lock.setReferenceCounted(false)
        lock.acquire(30_000L)
        try {
            if (number.isBlank()) {
                MeetingNotifications.post(app, "Meeting Mode \u2022 call declined", "$who called. There was no number to text.")
                return
            }
            if (!canSendSms(app)) {
                MeetingNotifications.post(
                    app,
                    "Meeting Mode \u2022 call declined",
                    "$who called. Allow SMS permission in Meeting Mode settings so HOPE can text callers."
                )
                return
            }
            val text = composeSms(app, contactName)
            try {
                sendSms(app, number, text)
                MeetingModePreferences.addLog(app, "HOPE texted $who", text)
                MeetingNotifications.post(
                    app,
                    "Meeting Mode \u2022 $who called",
                    "Call declined. HOPE texted them: $text"
                )
            } catch (error: Exception) {
                Log.w(TAG, "Could not send the Meeting Mode SMS.", error)
                MeetingModePreferences.addLog(app, "SMS failed for $who", error.message.orEmpty())
                MeetingNotifications.post(app, "Meeting Mode \u2022 $who called", "Call declined, but the SMS could not be sent.")
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private suspend fun composeSms(app: Context, contactName: String?): String {
        val jarvis = JarvisApp.instance
        val owner = jarvis.preferences.userName
        val situation = MeetingModePreferences.situation(app)
        val prompt = MeetingModeLogic.buildCallerSmsPrompt(
            ownerName = owner,
            situation = situation,
            untilText = MeetingModePreferences.untilText(app),
            customPrompt = MeetingModePreferences.customPrompt(app),
            callerName = contactName.orEmpty(),
            language = NotificationIntelligenceStore.replyLanguage(app),
            allowRepeated = MeetingModePreferences.allowRepeated(app)
        )
        val generated = try {
            withTimeoutOrNull(15_000L) {
                jarvis.aiRouter.generate(
                    mode = jarvis.preferences.aiMode,
                    systemInstruction = prompt,
                    context = "",
                    prompt = "Write the SMS now."
                ).text.trim().trim('"', ' ', '\n', '\r')
            }
        } catch (error: Exception) {
            Log.w(TAG, "AI could not write the Meeting Mode SMS; using the built-in text.", error)
            null
        }
        val clean = generated?.takeIf { it.isNotBlank() && it.contains(MeetingModeLogic.ASSISTANT_NAME, ignoreCase = true) }
        return SmartReplyLogic.shorten(
            clean ?: MeetingModeLogic.fallbackCallerSms(owner, situation),
            MeetingModeLogic.MAX_SMS_CHARS
        )
    }

    private fun sendSms(app: Context, number: String, text: String) {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
        else sms.sendTextMessage(number, null, text, null, null)
    }

    /** Contact name and whether the contact is starred; (null, false) without contacts permission. */
    private fun lookupContact(app: Context, number: String): Pair<String?, Boolean> {
        if (number.isBlank() ||
            app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) return null to false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            app.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to (cursor.getInt(1) == 1) else null to false
            } ?: (null to false)
        } catch (error: Exception) {
            Log.w(TAG, "Could not look up the caller in contacts.", error)
            null to false
        }
    }
}
