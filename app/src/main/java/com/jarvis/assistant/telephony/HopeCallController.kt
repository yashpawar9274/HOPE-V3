package com.jarvis.assistant.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.view.View
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import java.util.IdentityHashMap
import java.util.UUID

data class HopeCallSnapshot(
    val id: String,
    val caller: String,
    val number: String,
    val state: Int,
    val canHold: Boolean,
    val canMerge: Boolean,
    val muted: Boolean,
    val callCount: Int,
    val speakerOn: Boolean,
    val canSpeaker: Boolean,
    val connectTimeMillis: Long
)

object HopeCallController {
    const val ACTION_CALL_CHANGED = "com.jarvis.assistant.telephony.CALL_CHANGED"
    const val ACTION_ANSWER = "com.jarvis.assistant.telephony.ANSWER"
    const val ACTION_REJECT = "com.jarvis.assistant.telephony.REJECT"
    const val ACTION_HANG_UP = "com.jarvis.assistant.telephony.HANG_UP"
    const val EXTRA_CALL_ID = "call_id"

    private const val CHANNEL_ID = "hope_incoming_calls"
    private const val TAG = "HopeCallController"
    private const val NOTIFICATION_ID_BASE = 73500
    private val calls = LinkedHashMap<String, Call>()
    private val ids = IdentityHashMap<Call, String>()
    @Volatile private var inCallService: HopeInCallService? = null
    @Volatile private var muted = false
    @Volatile private var audioRoute = CallAudioState.ROUTE_EARPIECE
    @Volatile private var supportedAudioRoutes = CallAudioState.ROUTE_EARPIECE
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attachService(service: HopeInCallService) {
        inCallService = service
    }

    fun detachService(service: HopeInCallService) {
        if (inCallService === service) inCallService = null
    }

    fun updateAudioState(context: Context, state: CallAudioState?) {
        muted = state?.isMuted == true
        audioRoute = state?.route ?: CallAudioState.ROUTE_EARPIECE
        supportedAudioRoutes = state?.supportedRouteMask ?: CallAudioState.ROUTE_EARPIECE
        refreshAll(context)
    }

    @Synchronized
    fun track(call: Call): String {
        return ids[call] ?: UUID.randomUUID().toString().also { id ->
            ids[call] = id
            calls[id] = call
        }
    }

    fun refresh(context: Context, call: Call) {
        val snapshot = snapshot(context, call) ?: return
        try {
            postCallNotification(context, snapshot)
        } catch (error: Exception) {
            Log.e(TAG, "Could not post the incoming-call notification.", error)
            if (snapshot.state == Call.STATE_RINGING) {
                try {
                    context.startActivity(
                        Intent(context, IncomingCallActivity::class.java)
                            .putExtra(EXTRA_CALL_ID, snapshot.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (launchError: Exception) {
                    Log.e(TAG, "Could not open the incoming-call screen.", launchError)
                }
            }
        }
        broadcastCallChanged(context, snapshot.id)
    }

    @Synchronized
    fun incomingCall(context: Context, callId: String? = null): HopeCallSnapshot? {
        val entry = calls.entries.firstOrNull { (id, call) ->
            (callId == null || id == callId) && call.state == Call.STATE_RINGING
        } ?: return null
        return snapshot(context, entry.value)
    }

    @Synchronized
    fun currentCall(context: Context, callId: String? = null): HopeCallSnapshot? {
        val preferred = calls.entries.firstOrNull { (id, _) -> id == callId }
        val selected = preferred?.takeIf { it.value.state != Call.STATE_HOLDING }
            ?: calls.entries.lastOrNull {
                it.value.state == Call.STATE_RINGING ||
                    it.value.state == Call.STATE_ACTIVE ||
                    it.value.state == Call.STATE_DIALING ||
                    it.value.state == Call.STATE_CONNECTING
            }
            ?: preferred
            ?: return null
        val entry = selected
        return snapshot(context, entry.value)
    }

    fun answer(callId: String): Boolean = perform(callId) { call ->
        if (call.state != Call.STATE_RINGING) return@perform false
        call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        true
    }

    fun reject(callId: String): Boolean = perform(callId) { call ->
        if (call.state != Call.STATE_RINGING) return@perform false
        call.reject(false, null)
        true
    }

    fun hangUp(callId: String): Boolean = perform(callId) { call ->
        if (call.state == Call.STATE_DISCONNECTED) return@perform false
        call.disconnect()
        true
    }

    fun toggleHold(callId: String): Boolean = perform(callId) { call ->
        if (!call.details.can(Call.Details.CAPABILITY_HOLD)) return@perform false
        when (call.state) {
            Call.STATE_ACTIVE -> call.hold()
            Call.STATE_HOLDING -> call.unhold()
            else -> return@perform false
        }
        true
    }

    fun toggleMute(): Boolean {
        val service = inCallService ?: return false
        return try {
            service.setMuted(!muted)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Android Telecom rejected the mute request.", error)
            false
        }
    }

    fun toggleSpeaker(): Boolean {
        val service = inCallService ?: return false
        if (supportedAudioRoutes and CallAudioState.ROUTE_SPEAKER == 0) return false
        val targetRoute = if (audioRoute == CallAudioState.ROUTE_SPEAKER) {
            when {
                supportedAudioRoutes and CallAudioState.ROUTE_EARPIECE != 0 ->
                    CallAudioState.ROUTE_EARPIECE
                supportedAudioRoutes and CallAudioState.ROUTE_WIRED_HEADSET != 0 ->
                    CallAudioState.ROUTE_WIRED_HEADSET
                supportedAudioRoutes and CallAudioState.ROUTE_BLUETOOTH != 0 ->
                    CallAudioState.ROUTE_BLUETOOTH
                else -> return false
            }
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        return try {
            service.setAudioRoute(targetRoute)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Android Telecom rejected the audio-route request.", error)
            false
        }
    }

    fun sendDtmf(callId: String, digit: Char): Boolean = perform(callId) { call ->
        if (call.state != Call.STATE_ACTIVE || !digit.isDtmfDigit()) return@perform false
        call.playDtmfTone(digit)
        mainHandler.postDelayed({ call.stopDtmfTone() }, DTMF_TONE_DURATION_MS)
        true
    }

    fun addCall(context: Context, callId: String): Boolean {
        val call = synchronized(this) { calls[callId] } ?: return false
        if (call.state == Call.STATE_ACTIVE && call.details.can(Call.Details.CAPABILITY_HOLD)) {
            try {
                call.hold()
            } catch (error: Exception) {
                Log.e(TAG, "Could not hold the current call before adding a call.", error)
                return false
            }
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the dial pad to add a call.", error)
            false
        }
    }

    fun mergeCalls(callId: String): Boolean {
        val selected = synchronized(this) { calls[callId] } ?: return false
        val other = synchronized(this) {
            calls.entries.firstOrNull { (id, call) ->
                id != callId &&
                    call.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING) &&
                    selected.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) &&
                    call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
            }?.value
        } ?: return false
        return try {
            selected.conference(other)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Android Telecom rejected the conference merge.", error)
            false
        }
    }

    @Synchronized
    fun refreshAll(context: Context) {
        calls.values.toList().forEach { call -> refresh(context, call) }
    }

    @Synchronized
    fun remove(context: Context, call: Call) {
        val id = ids.remove(call) ?: return
        calls.remove(id)
        try {
            notificationManager(context).cancel(notificationId(id))
        } catch (error: Exception) {
            Log.w(TAG, "Could not clear the ended-call notification.", error)
        }
        broadcastCallChanged(context, id, removed = true)
    }

    private fun perform(callId: String, action: (Call) -> Boolean): Boolean {
        val call = synchronized(this) { calls[callId] } ?: return false
        return try {
            action(call)
        } catch (error: Exception) {
            Log.e(TAG, "Android Telecom rejected a call-control request.", error)
            false
        }
    }

    private fun snapshot(context: Context, call: Call): HopeCallSnapshot? {
        val id = synchronized(this) { ids[call] } ?: return null
        val number = call.details.handle
            ?.takeIf { it.scheme.equals("tel", ignoreCase = true) }
            ?.schemeSpecificPart
            .orEmpty()
        val telecomName = call.details.callerDisplayName?.toString()?.trim().orEmpty()
        val caller = telecomName.takeIf(String::isNotBlank)
            ?: lookupContactName(context, number)
            ?: number.takeIf(String::isNotBlank)
            ?: "Unknown caller"
        val otherCalls = synchronized(this) {
            calls.filterKeys { it != id }.values
        }
        val canHold = call.details.can(Call.Details.CAPABILITY_HOLD) &&
            call.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING)
        val canMerge = otherCalls.any { other ->
            other.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING) &&
                call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) &&
                other.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
        }
        return HopeCallSnapshot(
            id = id,
            caller = caller.take(120),
            number = number.take(80),
            state = call.state,
            canHold = canHold,
            canMerge = canMerge,
            muted = muted,
            callCount = synchronized(this) { calls.size },
            speakerOn = audioRoute == CallAudioState.ROUTE_SPEAKER,
            canSpeaker = supportedAudioRoutes and CallAudioState.ROUTE_SPEAKER != 0,
            connectTimeMillis = call.details.connectTimeMillis
        )
    }

    private fun Char.isDtmfDigit(): Boolean = this in '0'..'9' || this == '*' || this == '#'

    private fun broadcastCallChanged(context: Context, callId: String, removed: Boolean = false) {
        context.sendBroadcast(
            Intent(ACTION_CALL_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra("call_removed", removed)
        )
    }

    private fun lookupContactName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)
                else null
            }
        } catch (error: SecurityException) {
            Log.i(TAG, "Contacts permission is not granted; caller number will be shown.")
            null
        } catch (error: Exception) {
            Log.w(TAG, "Could not resolve caller name from contacts.", error)
            null
        }
    }

    private fun postCallNotification(context: Context, call: HopeCallSnapshot) {
        createChannel(context)
        val manager = notificationManager(context)
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) {
            manager.cancel(notificationId(call.id))
            return
        }

        val openIntent = Intent(context, IncomingCallActivity::class.java)
            .putExtra(EXTRA_CALL_ID, call.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            call.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val incoming = call.state == Call.STATE_RINGING
        val banner = buildBanner(context, call, incoming)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_jarvis)
            .setContentTitle(if (incoming) "Incoming call" else "Call in progress")
            .setContentText(call.caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(incoming || call.state == Call.STATE_ACTIVE)
            .setAutoCancel(!incoming)
            .setColor(android.graphics.Color.rgb(48, 209, 88))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(banner)
            .setCustomHeadsUpContentView(banner)
            .setCustomBigContentView(banner)
            .setContentIntent(fullScreenPendingIntent)

        if (incoming) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }
        manager.notify(notificationId(call.id), builder.build())

        if (incoming && !canShowCallNotification(context)) {
            try {
                context.startActivity(openIntent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not open the incoming-call screen without notification access.", error)
            }
        }
    }

    /**
     * iPhone-style call banner: avatar, caller, "mobile" / "calling..." / live timer and round
     * Decline + Accept buttons (a single red End button once the call is going on).
     */
    private fun buildBanner(context: Context, call: HopeCallSnapshot, incoming: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_call_banner)
        val initial = call.caller.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
        views.setTextViewText(R.id.ios_call_avatar, initial)
        views.setTextViewText(R.id.ios_call_name, call.caller)
        if (incoming) {
            views.setTextViewText(R.id.ios_call_subtitle, "mobile")
            views.setViewVisibility(R.id.ios_call_subtitle, View.VISIBLE)
            views.setViewVisibility(R.id.ios_call_chrono, View.GONE)
            views.setViewVisibility(R.id.ios_call_accept, View.VISIBLE)
            views.setContentDescription(R.id.ios_call_decline, "Decline call")
            views.setOnClickPendingIntent(
                R.id.ios_call_decline,
                actionPendingIntent(context, call.id, ACTION_REJECT)
            )
            views.setOnClickPendingIntent(
                R.id.ios_call_accept,
                actionPendingIntent(context, call.id, ACTION_ANSWER)
            )
        } else {
            val connected = (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING) &&
                call.connectTimeMillis > 0L
            views.setViewVisibility(R.id.ios_call_accept, View.GONE)
            views.setContentDescription(R.id.ios_call_decline, "End call")
            views.setOnClickPendingIntent(
                R.id.ios_call_decline,
                actionPendingIntent(context, call.id, ACTION_HANG_UP)
            )
            if (connected) {
                val elapsed = (System.currentTimeMillis() - call.connectTimeMillis).coerceAtLeast(0L)
                views.setChronometer(
                    R.id.ios_call_chrono,
                    SystemClock.elapsedRealtime() - elapsed,
                    null,
                    call.state == Call.STATE_ACTIVE
                )
                views.setViewVisibility(R.id.ios_call_chrono, View.VISIBLE)
                views.setTextViewText(R.id.ios_call_subtitle, "on hold")
                views.setViewVisibility(
                    R.id.ios_call_subtitle,
                    if (call.state == Call.STATE_HOLDING) View.VISIBLE else View.GONE
                )
            } else {
                views.setTextViewText(R.id.ios_call_subtitle, "calling\u2026")
                views.setViewVisibility(R.id.ios_call_subtitle, View.VISIBLE)
                views.setViewVisibility(R.id.ios_call_chrono, View.GONE)
            }
        }
        return views
    }

    private fun actionPendingIntent(context: Context, callId: String, action: String): PendingIntent {
        val intent = Intent(context, HopeCallActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode() xor callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call screen and answer/reject controls"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager(context).createNotificationChannel(channel)
    }

    private fun canShowCallNotification(context: Context): Boolean {
        val manager = notificationManager(context)
        if (!manager.areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.canUseFullScreenIntent()
        } else true
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notificationId(callId: String) = NOTIFICATION_ID_BASE + (callId.hashCode() and 0x7fff)

    private const val DTMF_TONE_DURATION_MS = 120L
}
