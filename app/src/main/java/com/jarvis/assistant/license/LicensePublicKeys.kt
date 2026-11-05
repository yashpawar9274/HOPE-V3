package com.jarvis.assistant.license

import android.content.Context
import android.util.Log

/**
 * Public keys that verify the licenceToken (ES256). Sources, in order:
 * 1. the copy bundled in assets/license_public_key.json (recommended),
 * 2. the copy fetched once from GET /api/license/public-key and cached.
 */
internal class LicensePublicKeys(private val context: Context, private val store: SecureStore) {

    fun load(): List<JwkKey> {
        val keys = ArrayList<JwkKey>()
        bundledJson()?.let { keys += Es256JwtVerifier.parseJwks(it) }
        store.cachedPublicKeys?.let { keys += Es256JwtVerifier.parseJwks(it) }
        return keys
    }

    /** Fetches the key set from the server and caches it. True when at least one key was received. */
    suspend fun refresh(api: LicenseApi): Boolean {
        return try {
            val result = api.get("/api/license/public-key")
            val keys = if (result.status == 200) Es256JwtVerifier.parseJwks(result.raw) else emptyList()
            if (keys.isEmpty()) {
                false
            } else {
                store.cachedPublicKeys = result.raw
                true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not fetch the licence public key.", error)
            false
        }
    }

    private fun bundledJson(): String? = try {
        context.assets.open(LicenseConfig.PUBLIC_KEY_ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (error: Exception) {
        null // not bundled: the cached copy from the server is used instead
    }

    private companion object {
        const val TAG = "LicensePublicKeys"
    }
}
