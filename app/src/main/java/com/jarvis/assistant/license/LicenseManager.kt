package com.jarvis.assistant.license

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.Locale

/**
 * HOPE licence logic (see hope_license_api.md):
 *  - verify()   POST /api/verify   on every app start and at least every 24 h
 *  - activate() POST /api/activate from the key screen
 *  - offline grace: the cached ES256 licenceToken is accepted when its signature verifies with the
 *    public key, sub == current Firebase uid, dev == this device id and exp is in the future.
 *
 * The uid is never sent by the app; the server takes it from the Firebase ID token.
 */
object LicenseManager {
    private const val TAG = "LicenseManager"

    private val api = LicenseApi()

    @Volatile private var storeInstance: SecureStore? = null
    @Volatile private var keysInstance: LicensePublicKeys? = null

    /** SystemClock.elapsedRealtime() of the last successful online verify/activate in this process. */
    @Volatile private var verifiedAtElapsed = 0L
    @Volatile private var verifiedUid: String? = null

    /**
     * Short grace window after the gate accepted the cached token because the server was unreachable
     * or returned an error. Without it MainActivity would send the user straight back to the gate
     * (stale verification + online) in an endless loop.
     */
    @Volatile private var graceUntilElapsed = 0L
    @Volatile private var graceUid: String? = null
    private const val OFFLINE_GRACE_WINDOW_MS = 15L * 60L * 1000L

    private fun store(context: Context): SecureStore =
        storeInstance ?: synchronized(this) {
            storeInstance ?: SecureStore(context.applicationContext).also { storeInstance = it }
        }

    private fun publicKeys(context: Context): LicensePublicKeys =
        keysInstance ?: synchronized(this) {
            keysInstance ?: LicensePublicKeys(context.applicationContext, store(context)).also { keysInstance = it }
        }

    // ---- identity ---------------------------------------------------------------------

    /** False until google-services.json is added to the app (Firebase would crash otherwise). */
    fun isFirebaseConfigured(context: Context): Boolean = try {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    } catch (error: Exception) {
        false
    }

    fun currentUser(context: Context): FirebaseUser? =
        if (isFirebaseConfigured(context)) FirebaseAuth.getInstance().currentUser else null

    fun deviceId(context: Context): String = store(context).deviceId()

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val prettyMaker = maker.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return if (model.startsWith(maker, ignoreCase = true) || maker.isEmpty()) model else "$prettyMaker $model"
    }

    // ---- verify -----------------------------------------------------------------------

    suspend fun verify(context: Context): VerifyOutcome {
        val app = context.applicationContext
        val user = currentUser(app) ?: return VerifyOutcome.SignInRequired
        val uid = user.uid
        val deviceId = deviceId(app)

        return when (val exchange = authorizedPost(app, "/api/verify", mapOf("deviceId" to deviceId))) {
            is Exchange.SignedOut -> {
                if (exchange.sessionRejected) signOut(app)
                VerifyOutcome.SignInRequired
            }
            is Exchange.Offline -> offlineOutcome(app, uid, deviceId, networkProblem = true, message = null)
            is Exchange.Done -> handleVerifyResponse(app, exchange.result, uid, deviceId)
        }
    }

    private suspend fun handleVerifyResponse(
        app: Context,
        result: LicenseApi.HttpResult,
        uid: String,
        deviceId: String
    ): VerifyOutcome {
        val body = result.body
        if (result.status == 200 && body != null && body["ok"] == true) {
            if (body["licensed"] == true) {
                acceptServerToken(app, body["licenseToken"] as? String, uid, deviceId)
                markVerified(app, uid)
                return VerifyOutcome.Licensed(LicenseInfo.fromMap(body["license"] as? Map<*, *>), offline = false)
            }
            // Not licensed (NO_LICENSE, DEVICE_NOT_ACTIVATED, LICENSE_REVOKED, LICENSE_EXPIRED): drop the cache.
            clearCachedLicense(app)
            return VerifyOutcome.NotLicensed(body["reason"] as? String ?: "NO_LICENSE")
        }
        // Temporary trouble (server error, rate limit, unreadable answer): the cached token may still be fine.
        return offlineOutcome(
            app, uid, deviceId,
            networkProblem = false,
            message = result.message ?: "The licence server returned an error (HTTP ${result.status})."
        )
    }

    private fun offlineOutcome(
        app: Context,
        uid: String,
        deviceId: String,
        networkProblem: Boolean,
        message: String?
    ): VerifyOutcome {
        if (offlineTokenIsValid(app, uid, deviceId)) {
            graceUntilElapsed = SystemClock.elapsedRealtime() + OFFLINE_GRACE_WINDOW_MS
            graceUid = uid
            return VerifyOutcome.Licensed(null, offline = true)
        }
        return if (networkProblem) VerifyOutcome.NeedsInternet
        else VerifyOutcome.Failure(message ?: "Could not verify your licence. Try again.")
    }

    // ---- activate ---------------------------------------------------------------------

    suspend fun activate(context: Context, formattedKey: String): ActivateOutcome {
        val app = context.applicationContext
        val user = currentUser(app) ?: return ActivateOutcome.SignInRequired
        val uid = user.uid
        val deviceId = deviceId(app)

        val fields = mapOf("key" to formattedKey, "deviceId" to deviceId, "deviceName" to deviceName())
        return when (val exchange = authorizedPost(app, "/api/activate", fields)) {
            is Exchange.SignedOut -> {
                if (exchange.sessionRejected) signOut(app)
                ActivateOutcome.SignInRequired
            }
            is Exchange.Offline ->
                ActivateOutcome.NetworkError("No internet connection. Connect to the internet and try again.")
            is Exchange.Done -> {
                val result = exchange.result
                val body = result.body
                if (result.status == 200 && body != null && body["ok"] == true && body["licensed"] == true) {
                    acceptServerToken(app, body["licenseToken"] as? String, uid, deviceId)
                    markVerified(app, uid)
                    ActivateOutcome.Success(LicenseInfo.fromMap(body["license"] as? Map<*, *>))
                } else {
                    ActivateOutcome.Rejected(
                        code = result.errorCode ?: "UNKNOWN",
                        message = rejectionMessage(result),
                        httpStatus = result.status
                    )
                }
            }
        }
    }

    /** The server's own message is always shown; a fallback is used only if it sent none. */
    private fun rejectionMessage(result: LicenseApi.HttpResult): String {
        val serverMessage = result.message?.takeIf { it.isNotBlank() }
        val base = serverMessage ?: when (result.errorCode) {
            "INVALID_KEY_FORMAT" -> "That is not a HOPE licence key."
            "DEVICE_ID_REQUIRED" -> "This device could not be identified. Restart HOPE and try again."
            "WRONG_ACCOUNT" -> "This key belongs to a different account. Sign in with the account you used to buy HOPE AI."
            "LICENSE_REVOKED" -> "This licence has been revoked."
            "LICENSE_EXPIRED" -> "This licence has expired."
            "KEY_NOT_FOUND" -> "Key not found. Check the key, or use the newest key if you generated a new one."
            "DEVICE_LIMIT" -> "This licence is already active on the maximum number of phones. Remove a device on the website dashboard."
            "RATE_LIMITED" -> "Too many attempts. Please wait a while and try again."
            else -> "Something went wrong (HTTP ${result.status}). Please try again."
        }
        if (result.errorCode != "DEVICE_LIMIT") return base
        val names = (result.body?.get("devices") as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return if (names.isEmpty()) base else base + "\nActive on: " + names.joinToString(", ")
    }

    // ---- gate guard (no network) ------------------------------------------------------

    /**
     * Fast, synchronous check for the main screen: may HOPE open right now?
     * True when this process verified online within 24 h, or when the cached licence token passes
     * the offline rules. Forces a re-verify (false) when the last verification is older than 24 h
     * and the phone is online.
     */
    fun guardAllows(context: Context): Boolean {
        val app = context.applicationContext
        val user = currentUser(app) ?: return false
        val uid = user.uid

        val verifiedHere = verifiedAtElapsed
        if (verifiedHere != 0L && verifiedUid == uid &&
            SystemClock.elapsedRealtime() - verifiedHere < LicenseConfig.REVERIFY_INTERVAL_MS
        ) return true

        if (!offlineTokenIsValid(app, uid, deviceId(app))) return false
        val lastVerified = store(app).lastVerifiedAtMs
        val stale = lastVerified == 0L || System.currentTimeMillis() - lastVerified > LicenseConfig.REVERIFY_INTERVAL_MS
        val inGrace = graceUid == uid && SystemClock.elapsedRealtime() < graceUntilElapsed
        return inGrace || !(stale && isOnline(app))
    }

    // ---- sign out ---------------------------------------------------------------------

    /** Signs out of Firebase and forgets the cached licence. The device id is kept. */
    fun signOut(context: Context) {
        val app = context.applicationContext
        try {
            if (isFirebaseConfigured(app)) FirebaseAuth.getInstance().signOut()
        } catch (error: Exception) {
            Log.w(TAG, "Firebase sign-out failed.", error)
        }
        clearCachedLicense(app)
    }

    private fun clearCachedLicense(app: Context) {
        store(app).clearLicense()
        verifiedAtElapsed = 0L
        verifiedUid = null
        graceUntilElapsed = 0L
        graceUid = null
    }

    private fun markVerified(app: Context, uid: String) {
        verifiedAtElapsed = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        verifiedUid = uid
        store(app).lastVerifiedAtMs = System.currentTimeMillis()
        touchClock(app)
    }

    private fun touchClock(app: Context) {
        val store = store(app)
        val now = System.currentTimeMillis() / 1000L
        if (now > store.latestSeenSeconds) store.latestSeenSeconds = now
    }

    // ---- licenceToken -----------------------------------------------------------------

    private sealed class Inspection {
        object Valid : Inspection()
        object BadSignature : Inspection()
        class Rejected(val verdict: LicenseTokenPolicy.Verdict) : Inspection()
    }

    private fun inspect(app: Context, token: String, uid: String, deviceId: String): Inspection {
        return when (val jwt = Es256JwtVerifier.verify(token, publicKeys(app).load())) {
            is JwtResult.Invalid -> Inspection.BadSignature
            is JwtResult.Valid -> {
                val verdict = LicenseTokenPolicy.evaluate(
                    claims = jwt.claims,
                    firebaseUid = uid,
                    deviceId = deviceId,
                    nowSeconds = System.currentTimeMillis() / 1000L,
                    latestSeenSeconds = store(app).latestSeenSeconds
                )
                if (verdict == LicenseTokenPolicy.Verdict.OK) Inspection.Valid else Inspection.Rejected(verdict)
            }
        }
    }

    private fun offlineTokenIsValid(app: Context, uid: String, deviceId: String): Boolean {
        val token = store(app).licenseToken ?: return false
        val valid = inspect(app, token, uid, deviceId) is Inspection.Valid
        if (valid) touchClock(app)
        return valid
    }

    /**
     * Stores the token from a successful verify/activate for offline use, after checking it against
     * the public key (fetching the key once if it is missing or has been rotated).
     */
    private suspend fun acceptServerToken(app: Context, token: String?, uid: String, deviceId: String) {
        if (token.isNullOrBlank()) return
        var inspection = inspect(app, token, uid, deviceId)
        if (inspection is Inspection.BadSignature && publicKeys(app).refresh(api)) {
            inspection = inspect(app, token, uid, deviceId)
        }
        if (inspection is Inspection.Valid) {
            store(app).licenseToken = token
        } else {
            Log.w(TAG, "The licence token from the server could not be validated locally; offline grace is off.")
        }
    }

    // ---- Firebase ID token + HTTP -----------------------------------------------------

    private sealed class Exchange {
        class Done(val result: LicenseApi.HttpResult) : Exchange()

        /** [sessionRejected] is true when the server refused a freshly refreshed token twice. */
        class SignedOut(val sessionRejected: Boolean) : Exchange()

        object Offline : Exchange()
    }

    private sealed class IdToken {
        class Value(val token: String) : IdToken()
        object SignedOut : IdToken()
        object Offline : IdToken()
    }

    private suspend fun idToken(user: FirebaseUser, forceRefresh: Boolean): IdToken = try {
        val token = user.getIdToken(forceRefresh).await().token
        if (token.isNullOrBlank()) IdToken.SignedOut else IdToken.Value(token)
    } catch (error: CancellationException) {
        throw error
    } catch (error: FirebaseNetworkException) {
        IdToken.Offline
    } catch (error: FirebaseAuthInvalidUserException) {
        IdToken.SignedOut // account deleted or disabled
    } catch (error: FirebaseAuthInvalidCredentialsException) {
        IdToken.SignedOut
    } catch (error: Exception) {
        Log.w(TAG, "Could not get a Firebase ID token.", error)
        IdToken.Offline
    }

    /**
     * POST with `Authorization: Bearer <Firebase ID token>`. On 401 the token is force-refreshed and
     * the call retried once; a second 401 means the user must sign in again.
     */
    private suspend fun authorizedPost(app: Context, path: String, fields: Map<String, String>): Exchange {
        val user = currentUser(app) ?: return Exchange.SignedOut(sessionRejected = false)
        var token = when (val first = idToken(user, forceRefresh = false)) {
            is IdToken.Value -> first.token
            IdToken.SignedOut -> return Exchange.SignedOut(sessionRejected = true)
            IdToken.Offline -> return Exchange.Offline
        }
        for (attempt in 0..1) {
            val result = try {
                api.post(path, token, fields)
            } catch (error: IOException) {
                return Exchange.Offline
            }
            if (result.status != 401) return Exchange.Done(result)
            if (attempt == 1) return Exchange.SignedOut(sessionRejected = true)
            token = when (val refreshed = idToken(user, forceRefresh = true)) {
                is IdToken.Value -> refreshed.token
                IdToken.SignedOut -> return Exchange.SignedOut(sessionRejected = true)
                IdToken.Offline -> return Exchange.Offline
            }
        }
        return Exchange.SignedOut(sessionRejected = true)
    }

    private fun isOnline(app: Context): Boolean = try {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (error: Exception) {
        false
    }
}
