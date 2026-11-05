package com.jarvis.assistant.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns the display off while the phone is held to the ear, like every phone app does.
 * It is only held while the call uses the earpiece; speaker, Bluetooth or a wired headset release it.
 */
class CallProximityLock(context: Context) {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lock: PowerManager.WakeLock? = null

    val isSupported: Boolean = try {
        power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
    } catch (error: Exception) {
        false
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        try {
            if (enabled) {
                val current = lock ?: power.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "hope:call-proximity"
                ).also {
                    it.setReferenceCounted(false)
                    lock = it
                }
                if (!current.isHeld) current.acquire(4 * 60 * 60 * 1000L)
            } else {
                release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not change the proximity screen-off lock.", error)
        }
    }

    @Synchronized
    fun release() {
        try {
            lock?.let {
                if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the proximity screen-off lock.", error)
        }
    }

    companion object {
        private const val TAG = "CallProximityLock"

        /** True when a Bluetooth or wired headset is connected, so the phone is not at the ear. */
        fun isHeadsetConnected(context: Context): Boolean = try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                when (it.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                    else -> false
                }
            }
        } catch (error: Exception) {
            false
        }
    }
}

/** Caller-specific background: the contact photo (blurred and dimmed) or a colour set from the name. */
object CallerBackground {
    private const val TAG = "CallerBackground"

    /** Three colours (top, middle, bottom) that are the same for the same caller every time. */
    fun gradientFor(name: String): IntArray {
        val hue = (abs(name.trim().lowercase().hashCode()) % 360).toFloat()
        return intArrayOf(
            Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.38f)),
            Color.HSVToColor(floatArrayOf((hue + 20f) % 360f, 0.60f, 0.20f)),
            Color.HSVToColor(floatArrayOf((hue + 45f) % 360f, 0.50f, 0.06f))
        )
    }

    /** Blocking: call from a background thread. Returns null when the caller has no photo. */
    fun loadBlurredPhoto(context: Context, number: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (number.isBlank() ||
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val photoUri = context.contentResolver.query(
                lookup,
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                null,
                null,
                null
            )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null

            val uri = Uri.parse(photoUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            blur(decoded, max(targetWidth, 1), max(targetHeight, 1))
        } catch (error: Throwable) {
            Log.w(TAG, "Could not load the caller photo background.", error)
            null
        }
    }

    /** Cheap, dependency-free blur: shrink a lot, then scale back up with filtering. */
    private fun blur(source: Bitmap, width: Int, height: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(
            source,
            max(source.width / 24, 12),
            max(source.height / 24, 12),
            true
        )
        return Bitmap.createScaledBitmap(small, width / 2, height / 2, true)
    }
}
