package com.jarvis.assistant.license

data class LicenseInfo(
    val id: String?,
    val plan: String?,
    val status: String?,
    val maxDevices: Int?,
    val validUntil: String?
) {
    companion object {
        fun fromMap(map: Map<*, *>?): LicenseInfo? {
            if (map == null) return null
            return LicenseInfo(
                id = map["id"] as? String,
                plan = map["plan"] as? String,
                status = map["status"] as? String,
                maxDevices = (map["maxDevices"] as? Number)?.toInt(),
                validUntil = map["validUntil"] as? String
            )
        }
    }
}

/** Result of POST /api/verify (or of the offline fallback). */
sealed class VerifyOutcome {
    /** [offline] is true when the cached licence token was used because the server was not reachable. */
    data class Licensed(val license: LicenseInfo?, val offline: Boolean) : VerifyOutcome()

    /** reason: NO_LICENSE, DEVICE_NOT_ACTIVATED, LICENSE_REVOKED or LICENSE_EXPIRED. */
    data class NotLicensed(val reason: String) : VerifyOutcome()

    /** Not signed in, or the Firebase session is no longer valid: go to Login. */
    object SignInRequired : VerifyOutcome()

    /** Offline and there is no valid cached licence token. */
    object NeedsInternet : VerifyOutcome()

    /** Server error and no valid cached licence token. */
    data class Failure(val message: String) : VerifyOutcome()
}

/** Result of POST /api/activate. */
sealed class ActivateOutcome {
    data class Success(val license: LicenseInfo?) : ActivateOutcome()

    /** The server refused the key. [message] is the server's text and is shown to the user as is. */
    data class Rejected(val code: String, val message: String, val httpStatus: Int) : ActivateOutcome()

    object SignInRequired : ActivateOutcome()

    data class NetworkError(val message: String) : ActivateOutcome()
}
