package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.GemmaBackend

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val secureApiPrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_API_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure API-key storage could not be initialized", e)
        null
    }
    private val gson = Gson()

    init {
        migrateApiKeyToEncryptedStorage()
        val current = prefs.getString(KEY_MODEL, null)
        if (current == null || current !in GeminiConstants.SUPPORTED_MODELS) {
            prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
        }
    }

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val ENCRYPTED_API_PREF_NAME = "jarvis_secure_api_prefs"
        private const val TAG = "AppPreferences"
        private const val DEFAULT_AI_SYSTEM_INSTRUCTION =
            "You are HOPE, a helpful assistant. Reply clearly and naturally. Support Hindi and English."
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_HUGGING_FACE_TOKEN = "key_hugging_face_token"
        private const val KEY_AI_MODE = "key_ai_mode"
        private const val KEY_CLOUD_IN_AUTOMATIC = "key_cloud_in_automatic"
        private const val KEY_AI_SYSTEM_INSTRUCTION = "key_ai_system_instruction"
        private const val KEY_CRITICAL_THINKING_ENABLED = "key_critical_thinking_enabled"
        private const val KEY_GEMMA_BACKEND = "key_gemma_backend"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_AUTOMATIC_PERSONA_SWITCHING = "key_automatic_persona_switching"
        private const val KEY_CONFIRM_VOICE_SWITCH = "key_confirm_voice_switch"
        private const val KEY_VOICE_PERSONA_CUSTOM_PREFIX = "key_voice_persona_custom_"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_WAKE_WORD_ENABLED = "key_wake_word_enabled"
        private const val KEY_WAKE_WORD_CONFIDENCE = "key_wake_word_confidence"
        private const val KEY_VOICE_CLIPBOARD_ENABLED = "key_voice_clipboard_enabled"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_LOCATION_AWARENESS_ENABLED = "key_location_awareness_enabled"
        private const val KEY_LOCATION_SHARING_ENABLED = "key_location_sharing_enabled"
        private const val KEY_LOCATION_GEOCODING_ENABLED = "key_location_geocoding_enabled"
        private const val KEY_WEATHER_LOCATION_SHARING_ENABLED = "key_weather_location_sharing_enabled"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
        private const val KEY_OWNER_VOICE_ENABLED = "key_owner_voice_enabled"
        private const val KEY_OWNER_VOICE_PROFILE = "key_owner_voice_profile"
        private const val KEY_OWNER_VOICE_SAMPLES = "key_owner_voice_samples"
        private const val KEY_OWNER_VOICE_THRESHOLD = "key_owner_voice_threshold"
        private const val KEY_OWNER_VOICE_SAMPLE_COUNT = "key_owner_voice_sample_count"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SILENT_EXECUTION_MODE = "key_silent_execution_mode"
        private const val KEY_TASK_OVERLAY_ENABLED = "key_task_overlay_enabled"
        private const val KEY_TASK_OVERLAY_AVATAR = "key_task_overlay_avatar"
        private const val KEY_TASK_OVERLAY_MODE = "key_task_overlay_mode"
        private const val KEY_TASK_OVERLAY_EDGE = "key_task_overlay_edge"
        private const val KEY_TASK_OVERLAY_FREE_POSITION = "key_task_overlay_free_position"
        private const val KEY_TASK_OVERLAY_X = "key_task_overlay_x"
        private const val KEY_TASK_OVERLAY_Y = "key_task_overlay_y"
        private const val KEY_APP_THEME = "key_app_theme"
        private const val KEY_CUSTOM_THEME_COLOR = "key_custom_theme_color"
        private const val KEY_ORB_ANIMATION = "key_orb_animation"
        private const val KEY_ORB_SPEED = "key_orb_speed"
        private const val KEY_ORB_GLOW = "key_orb_glow"
        private const val KEY_ORB_SCALE = "key_orb_scale"
        private const val KEY_ORB_HUE = "key_orb_hue"
        private const val KEY_CUSTOM_ENDPOINT = "key_custom_endpoint"
    }

    var isOwnerVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_VOICE_ENABLED, value).apply()

    var ownerVoiceThreshold: Float
        get() {
            val stored = prefs.getFloat(KEY_OWNER_VOICE_THRESHOLD, 0.70f)
            // Auto-migrate older builds that saved overly strict 0.50f threshold
            return if (!stored.isFinite() || stored < 0.60f) 0.70f else stored.coerceIn(0.62f, 0.78f)
        }
        set(value) {
            require(value.isFinite()) { "Voice threshold must be finite." }
            prefs.edit().putFloat(KEY_OWNER_VOICE_THRESHOLD, value.coerceIn(0.62f, 0.78f)).apply()
        }

    var ownerVoiceSampleCount: Int
        get() = prefs.getInt(KEY_OWNER_VOICE_SAMPLE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, value).apply()

    fun hasOwnerVoiceProfile(): Boolean {
        val master = getOwnerVoiceProfile()
        if (isValidVoiceVector(master)) return true
        return getOwnerVoiceSamples().any(::isValidVoiceVector)
    }

    fun getOwnerVoiceProfile(): FloatArray? {
        val json = prefs.getString(KEY_OWNER_VOICE_PROFILE, null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                .takeIf(::isValidVoiceVector)
        } catch (e: Exception) {
            null
        }
    }

    fun getOwnerVoiceSamples(): List<FloatArray> {
        val json = prefs.getString(KEY_OWNER_VOICE_SAMPLES, null) ?: return emptyList()
        return try {
            val root = org.json.JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (isValidVoiceVector(vec)) list.add(vec)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOwnerVoiceProfile(masterProfile: FloatArray, sampleVectors: List<FloatArray>) {
        require(isValidVoiceVector(masterProfile)) { "Owner voice profile is invalid." }
        require(sampleVectors.isNotEmpty() && sampleVectors.all {
            isValidVoiceVector(it) && it.size == masterProfile.size
        }) { "Owner voice samples must be valid vectors with matching dimensions." }
        val masterArr = org.json.JSONArray()
        for (v in masterProfile) {
            masterArr.put(v.toDouble())
        }

        val samplesArr = org.json.JSONArray()
        for (vec in sampleVectors) {
            val arr = org.json.JSONArray()
            for (v in vec) {
                arr.put(v.toDouble())
            }
            samplesArr.put(arr)
        }

        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, masterArr.toString())
            .putString(KEY_OWNER_VOICE_SAMPLES, samplesArr.toString())
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleVectors.size)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun saveOwnerVoiceProfile(profile: FloatArray, sampleCount: Int) {
        require(isValidVoiceVector(profile)) { "Owner voice profile is invalid." }
        require(sampleCount > 0) { "Owner voice sample count must be positive." }
        val arr = org.json.JSONArray()
        for (v in profile) {
            arr.put(v.toDouble())
        }
        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, arr.toString())
            .remove(KEY_OWNER_VOICE_SAMPLES)
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleCount)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun clearOwnerVoiceProfile() {
        prefs.edit()
            .remove(KEY_OWNER_VOICE_PROFILE)
            .remove(KEY_OWNER_VOICE_SAMPLES)
            .remove(KEY_OWNER_VOICE_SAMPLE_COUNT)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, false)
            .apply()
    }

    var apiKey: String
        get() = secureApiPrefs?.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) {
            val secure = checkNotNull(secureApiPrefs) { "Secure API-key storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) secure.edit().remove(KEY_API_KEY).commit()
            else secure.edit().putString(KEY_API_KEY, clean).commit()
            check(saved) { "Could not save API key to encrypted storage." }
        }

    val canStoreApiKeySecurely: Boolean get() = secureApiPrefs != null

    var huggingFaceToken: String
        get() = secureApiPrefs?.getString(KEY_HUGGING_FACE_TOKEN, "")?.trim() ?: ""
        set(value) {
            val secure = checkNotNull(secureApiPrefs) { "Secure token storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) {
                secure.edit().remove(KEY_HUGGING_FACE_TOKEN).commit()
            } else {
                secure.edit().putString(KEY_HUGGING_FACE_TOKEN, clean).commit()
            }
            check(saved) { "Could not save Hugging Face token to encrypted storage." }
        }

    var aiMode: AiMode
        get() = AiMode.fromPreference(prefs.getString(KEY_AI_MODE, AiMode.AUTOMATIC.name).orEmpty())
        set(value) = prefs.edit().putString(KEY_AI_MODE, value.name).apply()

    var cloudAllowedInAutomatic: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_IN_AUTOMATIC, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_IN_AUTOMATIC, value).apply()

    var gemmaBackend: GemmaBackend
        get() = GemmaBackend.fromPreference(prefs.getString(KEY_GEMMA_BACKEND, GemmaBackend.CPU.name).orEmpty())
        set(value) = prefs.edit().putString(KEY_GEMMA_BACKEND, value.name).apply()

    var aiSystemInstruction: String
        get() = prefs.getString(KEY_AI_SYSTEM_INSTRUCTION, DEFAULT_AI_SYSTEM_INSTRUCTION)
            ?: DEFAULT_AI_SYSTEM_INSTRUCTION
        set(value) {
            val clean = value.trim()
            require(clean.isNotBlank()) { "System instruction must not be blank." }
            require(clean.length <= 4000) { "System instruction cannot exceed 4,000 characters." }
            prefs.edit().putString(KEY_AI_SYSTEM_INSTRUCTION, clean).apply()
        }

    var isCriticalThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_CRITICAL_THINKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CRITICAL_THINKING_ENABLED, value).apply()

    private fun migrateApiKeyToEncryptedStorage() {
        val legacyKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacyKey.isEmpty()) return
        val secure = secureApiPrefs ?: run {
            prefs.edit().remove(KEY_API_KEY).commit()
            return
        }
        try {
            val existing = secure.getString(KEY_API_KEY, null)
            if (existing.isNullOrEmpty()) {
                check(secure.edit().putString(KEY_API_KEY, legacyKey).commit())
            }
            if (!secure.getString(KEY_API_KEY, null).isNullOrEmpty()) prefs.edit().remove(KEY_API_KEY).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy API-key migration to encrypted storage failed", e)
        }
    }

    private fun isValidVoiceVector(vector: FloatArray?): Boolean =
        vector != null && vector.size >= 2 && vector.all { it.isFinite() } && vector.any { it != 0f }

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            if (stored !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
                return GeminiConstants.DEFAULT_MODEL
            }
            return stored
        }
        set(value) {
            val toSave = if (value in GeminiConstants.SUPPORTED_MODELS) value else GeminiConstants.DEFAULT_MODEL
            prefs.edit().putString(KEY_MODEL, toSave).apply()
        }

    var language: String
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, GeminiConstants.DEFAULT_LANGUAGE) ?: GeminiConstants.DEFAULT_LANGUAGE
            return if (stored.contains("Auto", ignoreCase = true)) "Auto" else stored
        }
        set(value) {
            val clean = if (value.contains("Auto", ignoreCase = true)) "Auto" else value.trim()
            prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        }

    var voice: String
        get() = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT) ?: GeminiConstants.PERSONALITY_ASSISTANT
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var isAutomaticPersonaSwitchingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_PERSONA_SWITCHING, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOMATIC_PERSONA_SWITCHING, value).apply()

    var confirmBeforeVoiceSwitch: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_VOICE_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_VOICE_SWITCH, value).apply()

    fun getVoicePersonaCustomization(voice: String, field: String): String {
        if (voice !in GeminiConstants.SUPPORTED_VOICES) return ""
        require(field in setOf("name", "expertise", "tone"))
        return prefs.getString("$KEY_VOICE_PERSONA_CUSTOM_PREFIX${voice.lowercase()}_$field", "").orEmpty()
    }

    fun setVoicePersonaCustomization(voice: String, field: String, value: String) {
        require(voice in GeminiConstants.SUPPORTED_VOICES)
        require(field in setOf("name", "expertise", "tone"))
        val clean = value.trim()
        require(clean.length <= 160) { "Persona customization cannot exceed 160 characters." }
        val editor = prefs.edit()
        val key = "$KEY_VOICE_PERSONA_CUSTOM_PREFIX${voice.lowercase()}_$field"
        if (clean.isBlank()) editor.remove(key) else editor.putString(key, clean)
        editor.apply()
    }

    var userName: String
        get() {
            val name = prefs.getString(KEY_USER_NAME, "") ?: ""
            return when {
                name.isBlank() || name == "Salaam" -> "YASH"
                name.equals("Rehaan", ignoreCase = true) ||
                    name.equals("Rehaan Sir", ignoreCase = true) ||
                    name.equals("TechGPT X", ignoreCase = true) -> {
                    prefs.edit().putString(KEY_USER_NAME, "YASH").apply()
                    "YASH"
                }
                else -> name
            }
        }
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var appTheme: String
        get() {
            val stored = prefs.getString(KEY_APP_THEME, "sunset") ?: "sunset"
            return when (stored) {
                "light", "pearl_white" -> "light"
                "dark", "midnight_onyx", "cyberpunk_neon", "emerald_obsidian" -> "dark"
                "red" -> "red"
                "sunset", "rose_gold" -> "sunset"
                "custom" -> "custom"
                else -> "light"
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var customThemeColor: String
        get() = prefs.getString(KEY_CUSTOM_THEME_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_THEME_COLOR, value.trim()).apply()

    var orbAnimation: String
        get() {
            val valid = listOf("holographic_plasma", "cosmic_pulsar", "quantum_core", "cyber_ring", "siri_glow")
            val stored = prefs.getString(KEY_ORB_ANIMATION, "holographic_plasma") ?: "holographic_plasma"
            return if (stored in valid) stored else "holographic_plasma"
        }
        set(value) = prefs.edit().putString(KEY_ORB_ANIMATION, value).apply()

    var orbSpeed: Float
        get() = prefs.getFloat(KEY_ORB_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SPEED, value).apply()

    var orbGlow: Float
        get() = prefs.getFloat(KEY_ORB_GLOW, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_GLOW, value).apply()

    var orbScale: Float
        get() = prefs.getFloat(KEY_ORB_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SCALE, value).apply()

    var orbHue: Float
        get() = prefs.getFloat(KEY_ORB_HUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ORB_HUE, value).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply()

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var isLocationAwarenessEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_AWARENESS_ENABLED, false)
        set(value) {
            prefs.edit()
                .putBoolean(KEY_LOCATION_AWARENESS_ENABLED, value)
                .apply()
            if (!value) isLocationSharingEnabled = false
        }

    var isLocationSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_SHARING_ENABLED, false)
        set(value) {
            require(!value || isLocationAwarenessEnabled) {
                "Enable location awareness before sharing location with AI services."
            }
            prefs.edit().putBoolean(KEY_LOCATION_SHARING_ENABLED, value).apply()
        }

    var isLocationGeocodingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_GEOCODING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_GEOCODING_ENABLED, value).apply()

    var isWeatherLocationSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_LOCATION_SHARING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_LOCATION_SHARING_ENABLED, value).apply()

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var wakeWordConfidenceThreshold: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_CONFIDENCE, 0.80f).coerceIn(0.50f, 0.95f)
        set(value) {
            require(value.isFinite()) { "Wake-word confidence threshold must be finite." }
            prefs.edit().putFloat(KEY_WAKE_WORD_CONFIDENCE, value.coerceIn(0.50f, 0.95f)).apply()
        }

    var isVoiceClipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_CLIPBOARD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_CLIPBOARD_ENABLED, value).apply()

    var isSilentExecutionMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_EXECUTION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_EXECUTION_MODE, value).apply()

    var isTaskOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_ENABLED, value).apply()

    var taskOverlayAvatar: String
        get() = prefs.getString(KEY_TASK_OVERLAY_AVATAR, "ghost") ?: "ghost"
        set(value) {
            val available = listOf("alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle")
            prefs.edit().putString(KEY_TASK_OVERLAY_AVATAR, value.takeIf { it in available } ?: "ghost").apply()
        }

    /** always keeps the avatar visible while the service is alive; working limits it to active tasks. */
    var taskOverlayMode: String
        get() = prefs.getString(KEY_TASK_OVERLAY_MODE, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_MODE, if (value == "working") "working" else "always").apply()

    /** Preferred dock edge. Dragging the overlay updates this to the closest edge. */
    var taskOverlayEdge: String
        get() = prefs.getString(KEY_TASK_OVERLAY_EDGE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_EDGE, value.takeIf { it in setOf("left", "right", "top", "bottom") } ?: "right").apply()

    var taskOverlayFreePosition: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_FREE_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_FREE_POSITION, value).apply()

    var taskOverlayX: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_X, value.coerceAtLeast(0)).apply()

    var taskOverlayY: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_Y, value.coerceAtLeast(0)).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
