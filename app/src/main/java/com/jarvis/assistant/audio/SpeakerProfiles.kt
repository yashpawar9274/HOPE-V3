package com.jarvis.assistant.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeakerProfile(
    val id: String,
    val preferredName: String,
    val preferredAddress: String,
    val language: String,
    val embeddings: List<FloatArray>
)

data class SpeakerProfileData(
    val profiles: List<SpeakerProfile> = emptyList(),
    val enabled: Boolean = false,
    val distanceThreshold: Float = OwnerVoiceManager.DEFAULT_THRESHOLD
)

data class SpeakerMatch(
    val profile: SpeakerProfile? = null,
    val distance: Float? = null,
    val isAmbiguous: Boolean = false
)

object SpeakerProfileMatcher {
    const val MIN_THRESHOLD = OwnerVoiceManager.STRICT_THRESHOLD
    const val MAX_THRESHOLD = OwnerVoiceManager.RELAXED_THRESHOLD
    const val AMBIGUITY_MARGIN = 0.08f

    fun match(
        current: FloatArray,
        profiles: List<SpeakerProfile>,
        threshold: Float
    ): SpeakerMatch {
        if (!OwnerVoiceManager.isValidVector(current) || !threshold.isFinite()) return SpeakerMatch()

        val ranked = profiles.mapNotNull { profile ->
            val distance = profile.embeddings.asSequence()
                .filter { it.size == current.size && OwnerVoiceManager.isValidVector(it) }
                .map { OwnerVoiceManager.calculateCosineDistance(current, it) }
                .filter(Float::isFinite)
                .minOrNull()
                ?: return@mapNotNull null
            profile to distance
        }.sortedBy { it.second }

        val best = ranked.firstOrNull() ?: return SpeakerMatch()
        if (best.second > threshold) return SpeakerMatch(distance = best.second)
        val second = ranked.getOrNull(1)
        if (second != null && second.second - best.second <= AMBIGUITY_MARGIN) {
            return SpeakerMatch(distance = best.second, isAmbiguous = true)
        }
        return SpeakerMatch(profile = best.first, distance = best.second)
    }
}

object SpeakerProfileState {
    private val _data = MutableStateFlow(SpeakerProfileData())
    val data = _data.asStateFlow()

    internal fun publish(value: SpeakerProfileData) {
        _data.value = value
    }
}

class SpeakerProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyAlias = "${appContext.packageName}.speaker_profiles_v1"
    private val associatedData = "${appContext.packageName}:speaker-profiles:1".toByteArray(Charsets.UTF_8)

    @Synchronized
    fun read(): SpeakerProfileData {
        val encoded = preferences.getString(KEY_DATA, null)
        if (encoded == null) return SpeakerProfileData().also(SpeakerProfileState::publish)
        val encrypted = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("The encrypted speaker-profile record is damaged.", error)
        }
        check(encrypted.size > IV_LENGTH) { "The encrypted speaker-profile record is incomplete." }
        val iv = encrypted.copyOfRange(0, IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(IV_LENGTH, encrypted.size)
        try {
            val plain = cipher(Cipher.DECRYPT_MODE, key(), iv).doFinal(ciphertext)
            try {
                return decode(String(plain, Charsets.UTF_8)).also(SpeakerProfileState::publish)
            } finally {
                plain.fill(0)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            encrypted.fill(0)
        }
    }

    @Synchronized
    fun write(data: SpeakerProfileData) {
        validate(data)
        val iv = ByteArray(IV_LENGTH).also(SecureRandom()::nextBytes)
        val plain = encode(data).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            cipher(Cipher.ENCRYPT_MODE, key(), iv).doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val combined = iv + ciphertext
        val encoded = try {
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            combined.fill(0)
        }
        check(preferences.edit().putString(KEY_DATA, encoded).commit()) {
            "Speaker profiles could not be saved to protected storage."
        }
        SpeakerProfileState.publish(data)
    }

    @Synchronized
    fun deleteAll() {
        check(preferences.edit().remove(KEY_DATA).commit()) {
            "Speaker profiles could not be deleted from protected storage."
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        } finally {
            SpeakerProfileState.publish(SpeakerProfileData())
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(associatedData)
        }

    private fun encode(data: SpeakerProfileData): String = JSONObject().apply {
        put("enabled", data.enabled)
        put("distanceThreshold", data.distanceThreshold.toDouble())
        put("profiles", JSONArray().apply {
            data.profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("preferredName", profile.preferredName)
                    put("preferredAddress", profile.preferredAddress)
                    put("language", profile.language)
                    put("embeddings", JSONArray().apply {
                        profile.embeddings.forEach { vector ->
                            put(JSONArray().apply { vector.forEach { put(it.toDouble()) } })
                        }
                    })
                })
            }
        })
    }.toString()

    private fun decode(json: String): SpeakerProfileData {
        val root = JSONObject(json)
        val profilesJson = root.getJSONArray("profiles")
        check(profilesJson.length() <= MAX_PROFILES) { "Too many speaker profiles are stored." }
        val profiles = (0 until profilesJson.length()).map { index ->
            val item = profilesJson.getJSONObject(index)
            val embeddingsJson = item.getJSONArray("embeddings")
            check(embeddingsJson.length() in MIN_SAMPLES..MAX_SAMPLES) {
                "A speaker profile has an invalid sample count."
            }
            val vectors = (0 until embeddingsJson.length()).map { vectorIndex ->
                val values = embeddingsJson.getJSONArray(vectorIndex)
                check(values.length() in MIN_VECTOR_SIZE..MAX_VECTOR_SIZE) {
                    "A speaker embedding has an invalid size."
                }
                FloatArray(values.length()) { values.getDouble(it).toFloat() }.also {
                    check(OwnerVoiceManager.isValidVector(it)) { "A speaker embedding is invalid." }
                }
            }
            SpeakerProfile(
                id = item.getString("id"),
                preferredName = item.getString("preferredName"),
                preferredAddress = item.getString("preferredAddress"),
                language = item.getString("language"),
                embeddings = vectors
            )
        }
        return SpeakerProfileData(
            profiles = profiles,
            enabled = root.optBoolean("enabled", false),
            distanceThreshold = root.optDouble("distanceThreshold", OwnerVoiceManager.DEFAULT_THRESHOLD.toDouble())
                .toFloat()
                .coerceIn(SpeakerProfileMatcher.MIN_THRESHOLD, SpeakerProfileMatcher.MAX_THRESHOLD)
        ).also(::validate)
    }

    private fun validate(data: SpeakerProfileData) {
        require(data.profiles.size <= MAX_PROFILES) { "A maximum of $MAX_PROFILES speakers can be enrolled." }
        require(!data.enabled || data.profiles.isNotEmpty()) {
            "Personalized addressing cannot be enabled without an enrolled speaker."
        }
        require(data.distanceThreshold.isFinite() &&
            data.distanceThreshold in SpeakerProfileMatcher.MIN_THRESHOLD..SpeakerProfileMatcher.MAX_THRESHOLD
        ) { "The speaker matching threshold is invalid." }
        require(data.profiles.map { it.id }.distinct().size == data.profiles.size) {
            "Speaker profile IDs must be unique."
        }
        data.profiles.forEach { profile ->
            require(profile.id.isNotBlank() && profile.id.length <= 64)
            require(isSafeLabel(profile.preferredName, 50))
            require(isSafeLabel(profile.preferredAddress, 32))
            require(isSafeLabel(profile.language, 40))
            require(profile.embeddings.size in MIN_SAMPLES..MAX_SAMPLES) {
                "Each speaker needs three enrollment samples."
            }
            val dimension = profile.embeddings.first().size
            require(profile.embeddings.all {
                it.size == dimension && it.size in MIN_VECTOR_SIZE..MAX_VECTOR_SIZE &&
                    OwnerVoiceManager.isValidVector(it)
            }) { "Speaker embeddings must be valid vectors of the same size." }
        }
    }

    private fun isSafeLabel(value: String, maxLength: Int): Boolean =
        value.length in 1..maxLength &&
            value.matches(Regex("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .,\\u2019'\\-]*"))

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_SAMPLES = 3
        const val MAX_PROFILES = 12
        private const val MIN_VECTOR_SIZE = 64
        private const val MAX_VECTOR_SIZE = 512
        private const val IV_LENGTH = 12
        private const val PREFERENCES_NAME = "hope_speaker_profiles"
        private const val KEY_DATA = "encrypted_profiles_v1"

        fun newProfileId(): String = UUID.randomUUID().toString()
    }
}
