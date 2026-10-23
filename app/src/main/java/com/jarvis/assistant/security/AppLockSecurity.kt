package com.jarvis.assistant.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.ui.security.AppSecurityActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.KeyGenerator

enum class AppCredentialType(val label: String) {
    PIN("PIN"),
    PATTERN("Pattern"),
    PASSWORD("Password");

    companion object {
        fun fromStored(value: String): AppCredentialType =
            entries.firstOrNull { it.name == value } ?: PIN
    }
}

data class AppLockConfig(
    val enabled: Boolean = false,
    val type: AppCredentialType = AppCredentialType.PIN,
    val salt: String = "",
    val verifier: String = "",
    val biometricEnabled: Boolean = false,
    val lockTimeoutMinutes: Int = 0,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

sealed interface CredentialCheck {
    data object Accepted : CredentialCheck
    data object Rejected : CredentialCheck
    data class LockedOut(val remainingMillis: Long) : CredentialCheck
}

object AppCredentialPolicy {
    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val MIN_PATTERN_POINTS = 4
    private const val MAX_PIN_LENGTH = 12
    private const val MAX_PASSWORD_LENGTH = 128
    private const val LOCKOUT_THRESHOLD = 5
    private const val MAX_LOCKOUT_MILLIS = 15 * 60 * 1000L

    fun validate(type: AppCredentialType, value: CharArray): String? {
        return when (type) {
            AppCredentialType.PIN -> when {
                value.size !in 4..MAX_PIN_LENGTH -> "Use a PIN with 4 to 12 digits."
                value.any { !it.isDigit() } -> "A PIN can contain digits only."
                else -> null
            }
            AppCredentialType.PATTERN -> when {
                value.size !in MIN_PATTERN_POINTS..9 -> "Connect at least 4 different dots."
                value.any { it !in '0'..'8' } -> "The pattern contains an invalid dot."
                value.toSet().size != value.size -> "A pattern cannot reuse a dot."
                else -> null
            }
            AppCredentialType.PASSWORD -> when {
                value.size !in 8..MAX_PASSWORD_LENGTH ->
                    "Use an 8 to 128 character password."
                value.none(Char::isLetter) || value.none(Char::isDigit) ->
                    "Use both letters and numbers in the password."
                else -> null
            }
        }
    }

    fun createSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(16).also(random::nextBytes)

    fun createVerifier(value: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(value: CharArray, salt: ByteArray, expected: ByteArray): Boolean {
        val actual = createVerifier(value, salt)
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            actual.fill(0)
        }
    }

    fun lockoutDurationMillis(failedAttempts: Int): Long {
        if (failedAttempts < LOCKOUT_THRESHOLD) return 0L
        val exponent = (failedAttempts - LOCKOUT_THRESHOLD).coerceAtMost(5)
        return (30_000L shl exponent).coerceAtMost(MAX_LOCKOUT_MILLIS)
    }

    fun hasTimedOut(
        timeoutMinutes: Int,
        lastBackgroundAt: Long,
        now: Long,
        authenticated: Boolean
    ): Boolean {
        if (!authenticated || timeoutMinutes == 0) return true
        return lastBackgroundAt > 0L &&
            now - lastBackgroundAt >= timeoutMinutes.coerceAtLeast(0) * 60_000L
    }
}

private class AppLockVault(context: Context) {
    private val preferences = context.getSharedPreferences("hope_app_lock", Context.MODE_PRIVATE)
    private val keyAlias = "${context.packageName}.app_lock_v1"
    private val associatedData = "${context.packageName}:app-lock:1".toByteArray(Charsets.UTF_8)

    @Synchronized
    fun read(): AppLockConfig {
        val encrypted = preferences.getString(KEY_RECORD, null) ?: return AppLockConfig()
        val bytes = try {
            Base64.decode(encrypted, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("The encrypted app-lock record is damaged.", error)
        }
        if (bytes.size <= GCM_IV_LENGTH) {
            throw IllegalStateException("The encrypted app-lock record is incomplete.")
        }
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        val json = JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        val timeout = json.optInt("timeoutMinutes", 0)
        return AppLockConfig(
            enabled = json.optBoolean("enabled", false),
            type = AppCredentialType.fromStored(json.optString("type", "PIN")),
            salt = json.optString("salt", ""),
            verifier = json.optString("verifier", ""),
            biometricEnabled = json.optBoolean("biometricEnabled", false),
            lockTimeoutMinutes = timeout.takeIf { it in ALLOWED_TIMEOUTS } ?: 0,
            failedAttempts = json.optInt("failedAttempts", 0).coerceAtLeast(0),
            lockoutUntilMillis = json.optLong("lockoutUntilMillis", 0L).coerceAtLeast(0L)
        )
    }

    @Synchronized
    fun write(config: AppLockConfig) {
        val json = JSONObject()
            .put("enabled", config.enabled)
            .put("type", config.type.name)
            .put("salt", config.salt)
            .put("verifier", config.verifier)
            .put("biometricEnabled", config.biometricEnabled)
            .put("timeoutMinutes", config.lockTimeoutMinutes)
            .put("failedAttempts", config.failedAttempts)
            .put("lockoutUntilMillis", config.lockoutUntilMillis)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(create = true))
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        check(preferences.edit().putString(KEY_RECORD, payload).commit()) {
            "The encrypted app-lock preferences could not be saved."
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(KEY_RECORD).commit()) {
            "The app-lock record could not be cleared."
        }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private fun getKey(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "The protected app-lock key is unavailable. Recover with Android device authentication." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val KEY_RECORD = "encrypted_record"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_IV_LENGTH = 12
        private val ALLOWED_TIMEOUTS = setOf(0, 1, 5, 15)
    }
}

object AppLockManager {
    private val lock = Any()
    private val activities = mutableListOf<WeakReference<Activity>>()
    @Volatile private var vault: AppLockVault? = null
    @Volatile private var cachedConfig = AppLockConfig()
    @Volatile private var authenticated = false
    @Volatile private var vaultError: String? = null
    @Volatile private var lastBackgroundAt = 0L
    @Volatile private var lockActivityShowing = false
    @Volatile var isCredentialEntryVisible = false
        private set
    private var startedActivityCount = 0

    val config: AppLockConfig get() = cachedConfig
    val storageError: String? get() = vaultError
    val isLocked: Boolean get() = cachedConfig.enabled && (!authenticated || vaultError != null)
    val lockoutRemainingMillis: Long
        get() = (cachedConfig.lockoutUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    fun initialize(application: Application) {
        if (vault != null) return
        synchronized(lock) {
            if (vault != null) return
            val newVault = AppLockVault(application)
            vault = newVault
            try {
                cachedConfig = newVault.read()
                vaultError = null
            } catch (error: Exception) {
                vaultError = error.message ?: "The protected app-lock record is unavailable."
                cachedConfig = AppLockConfig(enabled = true)
                Log.e(TAG, "The protected app lock could not be read; private screens stay locked.", error)
            }
            authenticated = !cachedConfig.enabled && vaultError == null
            application.registerActivityLifecycleCallbacks(LifecycleCallbacks())
        }
    }

    suspend fun verify(value: CharArray): CredentialCheck = withContext(Dispatchers.Default) {
        val current = cachedConfig
        if (!current.enabled || vaultError != null) return@withContext CredentialCheck.Rejected
        val now = System.currentTimeMillis()
        if (current.lockoutUntilMillis > now) {
            return@withContext CredentialCheck.LockedOut(current.lockoutUntilMillis - now)
        }
        val verified = try {
            AppCredentialPolicy.matches(
                value,
                Base64.decode(current.salt, Base64.NO_WRAP),
                Base64.decode(current.verifier, Base64.NO_WRAP)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not verify app-lock credential.", error)
            false
        }
        synchronized(lock) {
            if (verified) {
                save(current.copy(failedAttempts = 0, lockoutUntilMillis = 0L))
                authenticated = true
                lastBackgroundAt = 0L
                updateSecureWindows()
                CredentialCheck.Accepted
            } else {
                val attempts = current.failedAttempts + 1
                val duration = AppCredentialPolicy.lockoutDurationMillis(attempts)
                val lockout = if (duration > 0L) now + duration else 0L
                save(current.copy(failedAttempts = attempts, lockoutUntilMillis = lockout))
                if (duration > 0L) CredentialCheck.LockedOut(duration)
                else CredentialCheck.Rejected
            }
        }
    }

    fun updateTimeout(timeoutMinutes: Int) {
        require(timeoutMinutes in setOf(0, 1, 5, 15)) { "Unsupported app-lock timeout." }
        synchronized(lock) {
            save(cachedConfig.copy(lockTimeoutMinutes = timeoutMinutes))
        }
    }

    fun updateBiometricEnabled(enabled: Boolean) {
        synchronized(lock) {
            require(cachedConfig.enabled) { "Enable the HOPE app lock before configuring biometric unlock." }
            save(cachedConfig.copy(biometricEnabled = enabled))
        }
    }

    suspend fun setCredential(
        type: AppCredentialType,
        credential: CharArray,
        timeoutMinutes: Int,
        biometricEnabled: Boolean = false
    ) = withContext(Dispatchers.Default) {
        AppCredentialPolicy.validate(type, credential)?.let { throw IllegalArgumentException(it) }
        val salt = AppCredentialPolicy.createSalt()
        var verifier: ByteArray? = null
        try {
            verifier = AppCredentialPolicy.createVerifier(credential, salt)
            val updated = AppLockConfig(
                enabled = true,
                type = type,
                salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                verifier = Base64.encodeToString(verifier, Base64.NO_WRAP),
                biometricEnabled = biometricEnabled,
                lockTimeoutMinutes = timeoutMinutes.takeIf { it in setOf(0, 1, 5, 15) } ?: 0
            )
            synchronized(lock) {
                save(updated)
                authenticated = true
                lastBackgroundAt = 0L
                updateSecureWindows()
            }
        } finally {
            salt.fill(0)
            verifier?.fill(0)
        }
    }

    fun unlockWithBiometric() {
        synchronized(lock) {
            val current = cachedConfig
            check(current.lockoutUntilMillis <= System.currentTimeMillis()) {
                "Wait for the app-lock timeout before using biometric unlock."
            }
            check(current.biometricEnabled) { "Biometric app unlock is not enabled." }
            save(current.copy(failedAttempts = 0, lockoutUntilMillis = 0L))
            authenticated = true
            lastBackgroundAt = 0L
            updateSecureWindows()
        }
    }

    fun disable() {
        synchronized(lock) {
            requireNotNull(vault).clear()
            cachedConfig = AppLockConfig(enabled = false)
            authenticated = true
            vaultError = null
            lastBackgroundAt = 0L
            updateSecureWindows()
        }
    }

    fun recoverAfterSystemAuthentication() {
        synchronized(lock) {
            requireNotNull(vault).clear()
            cachedConfig = AppLockConfig()
            vaultError = null
            authenticated = true
            lastBackgroundAt = 0L
            updateSecureWindows()
        }
    }

    fun updateLockActivityShowing(showing: Boolean) {
        lockActivityShowing = showing
    }

    fun updateCredentialEntryVisible(visible: Boolean) {
        isCredentialEntryVisible = visible
    }

    private fun save(config: AppLockConfig) {
        requireNotNull(vault).write(config)
        cachedConfig = config
        vaultError = null
    }

    private fun lockOnBackground() {
        if (!cachedConfig.enabled) return
        lastBackgroundAt = System.currentTimeMillis()
        if (cachedConfig.lockTimeoutMinutes == 0) {
            authenticated = false
            updateSecureWindows()
        }
    }

    private fun lockIfExpired() {
        if (!cachedConfig.enabled) return
        if (vaultError != null) {
            authenticated = false
            return
        }
        if (AppCredentialPolicy.hasTimedOut(
                cachedConfig.lockTimeoutMinutes,
                lastBackgroundAt,
                System.currentTimeMillis(),
                authenticated
            )
        ) {
            authenticated = false
            updateSecureWindows()
        }
    }

    private fun updateSecureWindows() {
        Handler(Looper.getMainLooper()).post {
            synchronized(activities) {
                val iterator = activities.iterator()
                while (iterator.hasNext()) {
                    val activity = iterator.next().get()
                    if (activity == null) {
                        iterator.remove()
                    } else if (activity is AppSecurityActivity ||
                        (cachedConfig.enabled && !authenticated)
                    ) {
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }

    private fun shouldExclude(activity: Activity): Boolean =
        activity is AppSecurityActivity ||
            activity.javaClass.name == "com.jarvis.assistant.telephony.IncomingCallActivity"

    private class LifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
            synchronized(AppLockManager.activities) {
                AppLockManager.activities += WeakReference(activity)
            }
            if (activity is AppSecurityActivity) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else if (AppLockManager.isLocked) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        override fun onActivityStarted(activity: Activity) {
            val wasBackgrounded = synchronized(AppLockManager.lock) {
                val wasBackground = AppLockManager.startedActivityCount == 0
                AppLockManager.startedActivityCount++
                wasBackground
            }
            if (wasBackgrounded) AppLockManager.lockIfExpired()
        }

        override fun onActivityResumed(activity: Activity) {
            if (AppLockManager.shouldExclude(activity)) return
            if (AppLockManager.isLocked && !AppLockManager.lockActivityShowing &&
                activity !is AppSecurityActivity
            ) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                AppLockManager.lockActivityShowing = true
                try {
                    activity.startActivity(
                        Intent(activity, AppSecurityActivity::class.java)
                            .putExtra(AppSecurityActivity.EXTRA_MODE, AppSecurityActivity.MODE_UNLOCK)
                    )
                } catch (error: Exception) {
                    AppLockManager.lockActivityShowing = false
                    Log.e(TAG, "Could not display the app-lock screen.", error)
                }
            } else if (!AppLockManager.isLocked &&
                activity !is AppSecurityActivity &&
                activity.javaClass.name != "com.jarvis.assistant.telephony.IncomingCallActivity"
            ) {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            val isBackground = synchronized(AppLockManager.lock) {
                AppLockManager.startedActivityCount =
                    (AppLockManager.startedActivityCount - 1).coerceAtLeast(0)
                AppLockManager.startedActivityCount == 0
            }
            if (isBackground) AppLockManager.lockOnBackground()
        }

        override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            synchronized(AppLockManager.activities) {
                AppLockManager.activities.removeAll { it.get() == null || it.get() === activity }
            }
            if (activity is AppSecurityActivity) AppLockManager.lockActivityShowing = false
        }
    }

    private const val TAG = "AppLockManager"
}
