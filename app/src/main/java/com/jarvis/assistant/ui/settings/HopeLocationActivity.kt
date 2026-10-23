package com.jarvis.assistant.ui.settings

import android.Manifest
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.HopeLocation
import com.jarvis.assistant.util.LocationRepository
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Date

class HopeLocationActivity : AppCompatActivity() {
    private val preferences by lazy { (application as JarvisApp).preferences }
    private val locationRepository by lazy { LocationRepository(this) }
    private lateinit var awarenessSwitch: SwitchCompat
    private lateinit var sharingSwitch: SwitchCompat
    private lateinit var geocodingSwitch: SwitchCompat
    private lateinit var weatherSharingSwitch: SwitchCompat
    private lateinit var diagnostics: TextView
    private var updatingSwitches = false

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it } && PermissionHelper.hasLocationPermission(this)
        if (granted) {
            refreshCurrentLocation()
        } else {
            preferences.isLocationAwarenessEnabled = false
            syncSwitches()
            diagnostics.text = "Location permission was not granted. Location awareness remains off."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(root)

        addText(root, "LOCATION AWARENESS", 22f, true)
        addText(
            root,
            "Location is requested only when you ask HOPE to use it or tap Refresh. HOPE does not continuously track in the background. Approximate or precise access is chosen in Android's permission dialog.",
            14f
        )

        awarenessSwitch = SwitchCompat(this).apply {
            text = "Enable location awareness"
            textSize = 15f
        }
        root.addView(awarenessSwitch, matchParentWrap())
        awarenessSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            preferences.isLocationAwarenessEnabled = enabled
            if (enabled && !PermissionHelper.hasLocationPermission(this)) {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else if (enabled) {
                diagnostics.text = "Location awareness is on. A fresh fix is obtained only when requested."
            } else {
                diagnostics.text = "Location awareness is off. The local diagnostic cache remains until cleared."
                syncSwitches()
            }
        }

        sharingSwitch = SwitchCompat(this).apply {
            text = "Share approximate locality with Gemini"
            textSize = 15f
        }
        root.addView(sharingSwitch, matchParentWrap())
        sharingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (!enabled) {
                preferences.isLocationSharingEnabled = false
                return@setOnCheckedChangeListener
            }
            sharingSwitch.isChecked = false
            if (!preferences.isLocationAwarenessEnabled) {
                Toast.makeText(this, "Enable location awareness first.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            AlertDialog.Builder(this)
                .setTitle("Share approximate location?")
                .setMessage("When you explicitly ask for your current location in voice chat or request location-aware text help, HOPE may send only a human-readable locality and fix metadata to Gemini for that request. Coordinates are not sent. Turn this off at any time.")
                .setPositiveButton("Allow") { _, _ ->
                    preferences.isLocationSharingEnabled = true
                    sharingSwitch.isChecked = true
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addText(root, "LOCATION DIAGNOSTICS", 12f, true)
        diagnostics = addText(root, "", 14f)
        addButton(root, "Get current location") {
            if (!preferences.isLocationAwarenessEnabled) {
                diagnostics.text = "Enable location awareness to request a location."
            } else if (!PermissionHelper.hasLocationPermission(this)) {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                refreshCurrentLocation()
            }
        }
        addButton(root, "Clear locally cached location") {
            locationRepository.clearCachedLocation()
            diagnostics.text = "Cached coordinates and locality were cleared from this app."
        }
        geocodingSwitch = SwitchCompat(this).apply {
            text = "Allow external geocoding to resolve locality"
            textSize = 15f
        }
        root.addView(geocodingSwitch, matchParentWrap())
        geocodingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (!enabled) {
                preferences.isLocationGeocodingEnabled = false
                locationRepository.clearCachedLocation()
                renderCachedLocation()
                return@setOnCheckedChangeListener
            }
            geocodingSwitch.isChecked = false
            AlertDialog.Builder(this)
                .setTitle("Allow reverse geocoding?")
                .setMessage("Android's geocoding service may use a network service to convert coordinates into a locality. Without this permission, coordinates remain local and a locality label may be unavailable.")
                .setPositiveButton("Allow") { _, _ ->
                    preferences.isLocationGeocodingEnabled = true
                    geocodingSwitch.isChecked = true
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        weatherSharingSwitch = SwitchCompat(this).apply {
            text = "Share coordinates with Open-Meteo for weather"
            textSize = 15f
        }
        root.addView(weatherSharingSwitch, matchParentWrap())
        weatherSharingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (!enabled) {
                preferences.isWeatherLocationSharingEnabled = false
                return@setOnCheckedChangeListener
            }
            weatherSharingSwitch.isChecked = false
            AlertDialog.Builder(this)
                .setTitle("Share location for weather?")
                .setMessage("To show local weather, HOPE sends your device coordinates to Open-Meteo and may use Android's geocoder to label the city. This is separate from sharing locality with Gemini. Allow this only if you want live local weather.")
                .setPositiveButton("Allow") { _, _ ->
                    preferences.isWeatherLocationSharingEnabled = true
                    weatherSharingSwitch.isChecked = true
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addText(
            root,
            "Coordinates remain on this device unless you separately authorize external geocoding or Open-Meteo weather sharing. If Gemini sharing is enabled, only a human-readable locality and timestamp are included in a new voice session; text chat receives locality only for location-relevant requests. Location is never attached to WhatsApp replies. Android, network, provider availability, and battery restrictions can delay or prevent a fix.",
            12f
        )
        syncSwitches()
        renderCachedLocation()
    }

    override fun onResume() {
        super.onResume()
        if (::awarenessSwitch.isInitialized) {
            syncSwitches()
            renderCachedLocation()
        }
    }

    private fun refreshCurrentLocation() {
        if (!preferences.isLocationAwarenessEnabled) return
        diagnostics.text = "Requesting a one-shot location fix…"
        lifecycleScope.launch {
            try {
                val location = locationRepository.getCurrentLocation(preferences.isLocationGeocodingEnabled)
                diagnostics.text = formatDiagnostics(location, isCached = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.text = "Could not obtain a fresh location: ${error.message ?: "location unavailable"}"
            }
        }
    }

    private fun renderCachedLocation() {
        val location = locationRepository.cachedLocation()
        diagnostics.text = if (location == null) {
            "No location is cached on this device."
        } else {
            formatDiagnostics(location, isCached = true)
        }
    }

    private fun formatDiagnostics(location: HopeLocation, isCached: Boolean): String {
        val ageMillis = (System.currentTimeMillis() - location.updatedAtMillis).coerceAtLeast(0)
        val ageLabel = if (ageMillis <= HopeLocation.MAX_CONTEXT_AGE_MILLIS) "recent snapshot" else "STALE snapshot — refresh before relying on it"
        val accuracy = if (location.hasKnownAccuracy) {
            "${location.accuracyMeters.toInt()} m${if (location.isPoorAccuracy) " (poor accuracy)" else ""}"
        } else {
            "unknown"
        }
        return buildString {
            append(if (isCached) "Cached " else "Fresh ")
            append(ageLabel)
            append("\nLatitude: ${location.latitude}")
            append("\nLongitude: ${location.longitude}")
            append("\nAccuracy: $accuracy")
            append("\nUpdated: ${DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(location.updatedAtMillis))}")
            append("\nLocality: ${location.locality ?: "Unavailable (reverse geocoding may need network)"}")
        }
    }

    private fun syncSwitches() {
        updatingSwitches = true
        awarenessSwitch.isChecked = preferences.isLocationAwarenessEnabled
        sharingSwitch.isChecked = preferences.isLocationSharingEnabled
        geocodingSwitch.isChecked = preferences.isLocationGeocodingEnabled
        weatherSharingSwitch.isChecked = preferences.isWeatherLocationSharingEnabled
        updatingSwitches = false
    }

    private fun addText(parent: LinearLayout, text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(resolveThemeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
            parent.addView(this, matchParentWrap())
        }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            parent.addView(this, matchParentWrap())
        }
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun matchParentWrap() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
