package com.jarvis.assistant.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HopeLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val updatedAtMillis: Long,
    val locality: String?
) {
    val hasKnownAccuracy: Boolean
        get() = accuracyMeters >= 0f && accuracyMeters.isFinite()

    val isPoorAccuracy: Boolean
        get() = !hasKnownAccuracy || accuracyMeters > POOR_ACCURACY_METERS

    fun sharedContext(): String =
        locality?.takeIf(String::isNotBlank)?.let {
            val accuracy = if (hasKnownAccuracy) {
                "Reported accuracy: about ${accuracyMeters.toInt()} metres."
            } else {
                "Reported accuracy is unavailable."
            }
            "Approximate locality: $it. Snapshot time: ${Date(updatedAtMillis)}. $accuracy " +
                "This is a snapshot, not guaranteed live location; do not describe it as current when stale."
        }.orEmpty()

    companion object {
        const val POOR_ACCURACY_METERS = 1000f
        const val MAX_CONTEXT_AGE_MILLIS = 5 * 60 * 1000L
    }
}

class LocationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    fun cachedLocation(): HopeLocation? {
        val lat = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val lon = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val accuracy = preferences.getFloat(KEY_ACCURACY, Float.NaN)
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
        if (!lat.isFinite() || !lon.isFinite() || !accuracy.isFinite() || updatedAt <= 0L) return null
        return HopeLocation(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            updatedAtMillis = updatedAt,
            locality = preferences.getString(KEY_LOCALITY, null)
        )
    }

    suspend fun getCurrentLocation(allowExternalGeocoding: Boolean = false): HopeLocation = withContext(Dispatchers.IO) {
        val fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        check(fineGranted || coarseGranted) { "Grant approximate or precise location permission first." }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: error("Android location service is unavailable.")
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (!fineGranted) add(LocationManager.PASSIVE_PROVIDER)
        }.distinct().filter { provider ->
            try {
                manager.isProviderEnabled(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not check location provider $provider.", error)
                false
            }
        }
        check(providers.isNotEmpty()) { "Android location services are disabled. Enable Location and try again." }

        val lastKnown = providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (error: SecurityException) {
                Log.w(TAG, "Location permission changed while reading last-known location.", error)
                null
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not read last-known location from $provider.", error)
                null
            }
        }.filter { location ->
            val ageMillis = System.currentTimeMillis() - location.time
            location.time > 0L &&
                ageMillis in 0..MAX_CACHED_FIX_AGE_MILLIS &&
                location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0
        }.maxByOrNull(Location::getTime)
        if (lastKnown != null) {
            val snapshot = toSnapshot(lastKnown, allowExternalGeocoding)
            cache(snapshot)
            return@withContext snapshot
        }

        val request = LocationRequestCompat.Builder(0L)
            .setQuality(
                if (fineGranted) LocationRequestCompat.QUALITY_HIGH_ACCURACY
                else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
            )
            .setMaxUpdates(1)
            .build()

        val location = withTimeout(LOCATION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListenerCompat {
                    override fun onLocationChanged(location: Location) {
                        val ageMillis = System.currentTimeMillis() - location.time
                        if (
                            location.time <= 0L ||
                            ageMillis !in 0..MAX_FIX_AGE_MILLIS ||
                            !location.latitude.isFinite() ||
                            !location.longitude.isFinite() ||
                            location.latitude !in -90.0..90.0 ||
                            location.longitude !in -180.0..180.0
                        ) {
                            Log.w(TAG, "Ignoring stale or invalid fix from a location provider.")
                            return
                        }
                        try {
                            LocationManagerCompat.removeUpdates(manager, this)
                        } catch (error: Exception) {
                            Log.w(TAG, "Could not remove one-shot location listener after receiving a fix.", error)
                        }
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
                continuation.invokeOnCancellation {
                    try {
                        LocationManagerCompat.removeUpdates(manager, listener)
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not remove one-shot location listener after cancellation.", error)
                    }
                }

                var registeredProviders = 0
                for (provider in providers) {
                    if (continuation.isCompleted) break
                    try {
                        LocationManagerCompat.requestLocationUpdates(
                            manager,
                            provider,
                            request,
                            listener,
                            Looper.getMainLooper()
                        )
                        registeredProviders++
                    } catch (error: SecurityException) {
                        if (registeredProviders == 0 && !continuation.isCompleted) {
                            continuation.resumeWithException(
                                IllegalStateException("Location permission is no longer available.", error)
                            )
                        }
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Could not request a fix from $provider.", error)
                    }
                }
                if (registeredProviders == 0 && !continuation.isCompleted) {
                    continuation.resumeWithException(
                        IllegalStateException("No enabled location provider could provide a location.")
                    )
                }
            }
        }

        val snapshot = toSnapshot(location, allowExternalGeocoding)
        cache(snapshot)
        snapshot
    }

    fun clearCachedLocation() {
        check(preferences.edit().clear().commit()) { "Could not clear cached location." }
    }

    private fun cache(location: HopeLocation) {
        check(preferences.edit()
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putFloat(KEY_ACCURACY, location.accuracyMeters)
            .putLong(KEY_UPDATED_AT, location.updatedAtMillis)
            .putString(KEY_LOCALITY, location.locality)
            .commit()) { "Could not save the local location diagnostic cache." }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun toSnapshot(location: Location, allowExternalGeocoding: Boolean): HopeLocation {
        val ageMillis = System.currentTimeMillis() - location.time
        check(
            location.time > 0L &&
                ageMillis in 0..MAX_FIX_AGE_MILLIS &&
                location.latitude.isFinite() &&
                location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 &&
                location.longitude in -180.0..180.0
        ) {
            "Android returned an invalid or stale location fix. Refresh and try again."
        }
        return HopeLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy
                .takeIf { location.hasAccuracy() && it >= 0f && it.isFinite() }
                ?: -1f,
            updatedAtMillis = location.time,
            locality = if (allowExternalGeocoding) resolveLocality(location) else null
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveLocality(location: Location): String? {
        return try {
            Geocoder(appContext, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                ?.takeIf(String::isNotBlank)
        } catch (error: Exception) {
            Log.w(TAG, "Reverse geocoding was unavailable; coordinates remain local.", error)
            null
        }
    }

    private companion object {
        const val TAG = "LocationRepository"
        const val CACHE_PREFS = "hope_location_cache"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_LOCALITY = "locality"
        const val LOCATION_TIMEOUT_MILLIS = 15_000L
        const val MAX_CACHED_FIX_AGE_MILLIS = 15_000L
        const val MAX_FIX_AGE_MILLIS = 2 * 60 * 1000L
    }
}

object LocationQuestionPolicy {
    private val currentLocationQuestion = Regex(
        """\b(where am i|where are we|my current location|my location(?: right now)?|my coordinates|what(?: is|'s) my location|meri location kya hai|main kahan hoon|main kaha hoon)\b""",
        RegexOption.IGNORE_CASE
    )

    private val locationRelevantQuestion = Regex(
        """\b(near me|nearby|around here|in my area|my current location|my location(?: right now)?|where am i|local weather|weather near me|meri location kya hai|main kahan hoon|main kaha hoon)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isCurrentLocationQuestion(text: String): Boolean = currentLocationQuestion.containsMatchIn(text)

    fun isLocationRelevant(text: String): Boolean = locationRelevantQuestion.containsMatchIn(text)
}
