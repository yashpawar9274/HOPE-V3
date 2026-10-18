package com.jarvis.assistant.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class HopeInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val openedCallScreens = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        HopeCallController.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        HopeCallController.track(call)
        openCallScreenIfReady(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                HopeCallController.refresh(this@HopeInCallService, call)
                openCallScreenIfReady(call)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                HopeCallController.refresh(this@HopeInCallService, call)
                openCallScreenIfReady(call)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        HopeCallController.refreshAll(this)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { callback ->
            try {
                call.unregisterCallback(callback)
            } catch (error: Exception) {
                Log.w(TAG, "Could not unregister call-state callback.", error)
            }
        }
        val callId = HopeCallController.track(call)
        HopeCallController.remove(this, call)
        openedCallScreens.remove(callId)
        HopeCallController.refreshAll(this)
        super.onCallRemoved(call)
    }

    private fun openCallScreenIfReady(call: Call) {
        if (call.state !in setOf(
                Call.STATE_RINGING,
                Call.STATE_DIALING,
                Call.STATE_CONNECTING,
                Call.STATE_ACTIVE,
                Call.STATE_HOLDING
            )
        ) return
        val id = HopeCallController.track(call)
        if (!openedCallScreens.add(id)) return
        try {
            startActivity(
                Intent(this, IncomingCallActivity::class.java)
                    .putExtra(HopeCallController.EXTRA_CALL_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (error: Exception) {
            openedCallScreens.remove(id)
            Log.w(TAG, "Could not open the in-call screen; call controls remain in the notification.", error)
        }
    }

    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        HopeCallController.updateAudioState(this, audioState)
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) ->
            try {
                call.unregisterCallback(callback)
            } catch (error: Exception) {
                Log.w(TAG, "Could not unregister call-state callback during shutdown.", error)
            }
        }
        callbacks.clear()
        HopeCallController.detachService(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "HopeInCallService"
    }
}
