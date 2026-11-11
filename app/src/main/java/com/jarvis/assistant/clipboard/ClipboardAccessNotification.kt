package com.jarvis.assistant.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.clipboard.SmartClipboardActivity

object ClipboardAccessNotification {
    private const val CHANNEL_ID = "smart_clipboard_access"
    private const val NOTIFICATION_ID = 7104

    fun show(
        context: Context,
        request: SmartClipboardRequest = SmartClipboardRequest.EXPLAIN
    ): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Clipboard access",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Open HOPE to read copied content after a voice request"
                    }
                )
            }
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)
            ) {
                return false
            }
            val intent = Intent(context, SmartClipboardActivity::class.java).apply {
                putExtra(SmartClipboardActivity.EXTRA_READ_ON_RESUME, true)
                putExtra(SmartClipboardActivity.EXTRA_REQUEST, request.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            notifications.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sparkle)
                    .setContentTitle("HOPE is ready to read your clipboard")
                    .setContentText("Tap to open HOPE and analyze the copied content.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
