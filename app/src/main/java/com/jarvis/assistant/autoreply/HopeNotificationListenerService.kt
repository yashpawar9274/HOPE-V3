package com.jarvis.assistant.autoreply

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.util.HopeSpeech
import java.util.UUID

class HopeNotificationListenerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        HopeSpeech.initialize(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateConnectionState(true)
        try {
            activeNotifications.orEmpty().forEach { sbn ->
                if (sbn.packageName == packageName) return@forEach
                NotificationIntelligenceStore.rememberObservedApp(
                    applicationContext,
                    sbn.packageName,
                    sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
                NotificationIntelligenceProcessor.process(
                    applicationContext,
                    sbn,
                    ::announce,
                    allowAutoReply = false
                )
            }
            publishInboxChanged()
        } catch (error: Exception) {
            Log.e(TAG, "Could not load currently active notifications after listener reconnect.", error)
        }
    }

    override fun onListenerDisconnected() {
        updateConnectionState(false)
        try {
            requestRebind(ComponentName(this, HopeNotificationListenerService::class.java))
        } catch (error: Exception) {
            Log.e(TAG, "Could not request notification listener reconnection.", error)
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        try {
            NotificationIntelligenceStore.rememberObservedApp(
                applicationContext,
                sbn.packageName,
                sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            NotificationIntelligenceProcessor.process(applicationContext, sbn, ::announce)
            publishInboxChanged()
        } catch (error: Exception) {
            Log.e(TAG, "Could not process a posted notification.", error)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            NotificationIntelligenceStore.markNotificationDismissed(applicationContext, sbn.key)
            publishInboxChanged()
        } catch (error: Exception) {
            Log.e(TAG, "Could not update notification dismissal state.", error)
        }
    }

    private fun announce(
        text: String,
        language: String,
        onComplete: (Boolean) -> Unit
    ): Boolean {
        if (!NotificationIntelligenceStore.areAnnouncementsEnabled(this)) {
            NotificationIntelligenceStore.setSpeechStatus(this, "Announcements are globally disabled.")
            return false
        }
        if (NotificationIntelligenceStore.isPaused(this)) {
            NotificationIntelligenceStore.setSpeechStatus(this, "Notification intelligence is paused.")
            return false
        }
        val blockReason = HopeSpeech.announcementBlockReason(
            this,
            NotificationIntelligenceStore.announcementsHeadphonesOnly(this)
        )
        if (blockReason != null) {
            NotificationIntelligenceStore.setSpeechStatus(this, blockReason)
            return false
        }
        val accepted = HopeSpeech.speak(
            this,
            text,
            language,
            "hope-notification-${UUID.randomUUID()}",
            requireAudioFocus = true
        ) { completed ->
            val status = if (completed) {
                "Last HOPE announcement played successfully."
            } else {
                "The HOPE speech request did not complete. It may be retried if the notification is delivered again."
            }
            try {
                NotificationIntelligenceStore.setSpeechStatus(this, status)
            } catch (error: Exception) {
                Log.e(TAG, "Could not update announcement diagnostics.", error)
            } finally {
                mainHandler.post { onComplete(completed) }
            }
        }
        if (accepted) {
            NotificationIntelligenceStore.setSpeechStatus(this, "HOPE announcement queued.")
        } else {
            NotificationIntelligenceStore.setSpeechStatus(this, HopeSpeech.status(this))
        }
        return accepted
    }

    private fun updateConnectionState(connected: Boolean) {
        try {
            NotificationIntelligenceStore.setListenerConnected(applicationContext, connected)
            publishInboxChanged()
        } catch (error: Exception) {
            Log.e(TAG, "Could not update notification listener connection state.", error)
        }
    }

    private fun publishInboxChanged() {
        sendBroadcast(
            Intent(NotificationInboxEvents.ACTION_CHANGED)
                .setPackage(packageName)
        )
    }

    override fun onDestroy() {
        try {
            NotificationIntelligenceStore.setListenerConnected(applicationContext, false)
        } catch (error: Exception) {
            Log.e(TAG, "Could not clear notification listener connection state.", error)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "HopeNotificationListener"
    }
}
