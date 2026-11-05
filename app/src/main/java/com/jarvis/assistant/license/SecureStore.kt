package com.jarvis.assistant.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Licence data kept in EncryptedSharedPreferences: the random device id, the licenceToken (JWT),
 * the cached public keys and the verification timestamps. If the Android keystore cannot be used,
 * the token and keys are simply not cached (the device id falls back to plain private prefs).
 */
internal class SecureStore(context: Context) {
    private val app = context.applicationContext

    private val secure: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: Exception) {
            Log.e(TAG, "Encrypted licence storage is not available on this device.", error)
            null
        }
    }

    private val plain: SharedPreferences by lazy { app.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE) }

    /** Random UUID created once per install. Never an IMEI or other hardware id. */
    @Synchronized
    fun deviceId(): String {
        secure?.getString(KEY_DEVICE_ID, null)?.let { return it }
        plain.getString(KEY_DEVICE_ID, null)?.let { existing ->
            secure?.edit()?.putString(KEY_DEVICE_ID, existing)?.apply()
            return existing
        }
        val created = UUID.randomUUID().toString()
        val target = secure ?: plain
        target.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    var licenseToken: String?
        get() = secure?.getString(KEY_TOKEN, null)
        set(value) {
            val editor = secure?.edit() ?: return
            if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, value)
            editor.apply()
        }

    var cachedPublicKeys: String?
        get() = secure?.getString(KEY_PUBLIC_KEYS, null)
        set(value) {
            val editor = secure?.edit() ?: return
            if (value == null) editor.remove(KEY_PUBLIC_KEYS) else editor.putString(KEY_PUBLIC_KEYS, value)
            editor.apply()
        }

    /** Wall-clock time (ms) of the last successful online verify / activate. */
    var lastVerifiedAtMs: Long
        get() = secure?.getLong(KEY_LAST_VERIFIED, 0L) ?: plain.getLong(KEY_LAST_VERIFIED, 0L)
        set(value) {
            (secure ?: plain).edit().putLong(KEY_LAST_VERIFIED, value).apply()
        }

    /** Highest wall-clock time (seconds) seen so far; used to notice a clock set backwards. */
    var latestSeenSeconds: Long
        get() = secure?.getLong(KEY_LATEST_SEEN, 0L) ?: plain.getLong(KEY_LATEST_SEEN, 0L)
        set(value) {
            (secure ?: plain).edit().putLong(KEY_LATEST_SEEN, value).apply()
        }

    fun clearLicense() {
        licenseToken = null
        lastVerifiedAtMs = 0L
    }

    private companion object {
        const val TAG = "LicenseSecureStore"
        const val SECURE_FILE = "hope_license_secure"
        const val PLAIN_FILE = "hope_license_plain"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "license_token"
        const val KEY_PUBLIC_KEYS = "public_keys_json"
        const val KEY_LAST_VERIFIED = "last_verified_at_ms"
        const val KEY_LATEST_SEEN = "latest_seen_seconds"
    }
}
