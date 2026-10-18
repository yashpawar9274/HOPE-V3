package com.jarvis.assistant.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.JarvisApp
import java.util.ArrayDeque
import java.util.Locale

object HopeSpeech {
    private data class SpeechRequest(
        val text: String,
        val language: String,
        val utteranceId: String,
        val requireAudioFocus: Boolean,
        val onComplete: (Boolean) -> Unit
    )

    private const val TAG = "HopeSpeech"
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<SpeechRequest>()
    private val callbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initializationFailed = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var activeUtteranceId: String? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (engine != null || initializationFailed) return
            try {
                engine = TextToSpeech(context.applicationContext) { status ->
                    mainHandler.post {
                        synchronized(lock) {
                            if (status == TextToSpeech.SUCCESS) {
                                ready = true
                                configurePersonaVoice(context.applicationContext)
                                configureDefaultLanguage(context.applicationContext)
                                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(utteranceId: String?) = Unit

                                    override fun onDone(utteranceId: String?) {
                                        finishUtterance(utteranceId, true)
                                    }

                                    @Deprecated("Deprecated in Android")
                                    override fun onError(utteranceId: String?) {
                                        finishUtterance(utteranceId, false)
                                    }

                                    override fun onError(utteranceId: String?, errorCode: Int) {
                                        Log.w(TAG, "HOPE speech request failed (code=$errorCode).")
                                        finishUtterance(utteranceId, false)
                                    }
                                })
                                speakNext()
                            } else {
                                initializationFailed = true
                                ready = false
                                failQueuedRequests()
                                Log.e(TAG, "The shared HOPE speech engine could not initialize (status=$status).")
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                initializationFailed = true
                Log.e(TAG, "Could not initialize the shared HOPE speech engine.", error)
            }
        }
    }

    fun speak(
        context: Context,
        text: String,
        language: String,
        utteranceId: String,
        requireAudioFocus: Boolean = false,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        val cleanText = text.trim().take(1000)
        if (cleanText.isBlank() || utteranceId.isBlank()) return false
        initialize(context)
        synchronized(lock) {
            if (initializationFailed) return false
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                queue.forEach { request -> mainHandler.post { request.onComplete(false) } }
                queue.clear()
                callbacks.values.forEach { callback -> mainHandler.post { callback(false) } }
                callbacks.clear()
                activeUtteranceId = null
                engine?.stop()
                abandonAudioFocus()
            }
            queue.removeAll { it.utteranceId == utteranceId }
            callbacks.remove(utteranceId)
            queue.addLast(
                SpeechRequest(cleanText, language, utteranceId, requireAudioFocus, onComplete)
            )
            if (ready) speakNext()
        }
        return true
    }

    fun stop(utteranceId: String? = null) {
        synchronized(lock) {
            if (utteranceId == null) {
                queue.forEach { request -> mainHandler.post { request.onComplete(false) } }
                queue.clear()
                callbacks.values.forEach { callback -> mainHandler.post { callback(false) } }
                callbacks.clear()
                activeUtteranceId = null
                engine?.stop()
                abandonAudioFocus()
                return
            }
            val wasActive = activeUtteranceId == utteranceId
            queue.removeAll { it.utteranceId == utteranceId }
            callbacks.remove(utteranceId)?.let { callback -> mainHandler.post { callback(false) } }
            if (wasActive) {
                activeUtteranceId = null
                engine?.stop()
                abandonAudioFocus()
                speakNext()
            }
        }
    }

    fun isSpeaking(): Boolean = synchronized(lock) {
        queue.isNotEmpty() || engine?.isSpeaking == true
    }

    fun status(context: Context): String {
        initialize(context)
        return synchronized(lock) {
            when {
                initializationFailed -> "HOPE speech engine is unavailable. Check the installed Android speech service."
                !ready -> "HOPE speech engine is initializing."
                else -> {
                    val selectedVoice = try {
                        (context.applicationContext as JarvisApp).preferences.voice
                    } catch (_: Exception) {
                        "HOPE"
                    }
                    val matchingVoice = engine?.voices.orEmpty().any {
                        it.name.equals(selectedVoice, ignoreCase = true) ||
                            it.name.contains(selectedVoice, ignoreCase = true)
                    }
                    if (matchingVoice) {
                        "Shared HOPE speech engine ready with the configured local voice."
                    } else {
                        "Shared local speech engine ready. Gemini Live voice “$selectedVoice” is cloud-only; local announcements use the device TTS voice."
                    }
                }
            }
        }
    }

    fun canAnnounce(context: Context): Boolean = synchronized(lock) {
        initialize(context)
        ready && !initializationFailed
    }

    fun announcementBlockReason(context: Context, headphonesOnly: Boolean): String? {
        if (!canAnnounce(context)) return status(context)
        if (com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) {
            return "Notification speech is paused while a HOPE voice conversation is active."
        }
        if (com.jarvis.assistant.telephony.HopeCallController.currentCall(context) != null) {
            return "Notification speech is paused while a phone call is active."
        }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (manager.mode != AudioManager.MODE_NORMAL) {
            return "Notification speech is paused because another call or communication audio session is active."
        }
        if (headphonesOnly && !hasHeadphones(context)) {
            return "Connect headphones to hear notification announcements."
        }
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted &&
                notificationManager.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                return "Notification speech is paused by Do Not Disturb."
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not inspect Do Not Disturb state; Android audio focus remains in effect.", error)
        }
        return null
    }

    fun hasHeadphones(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
    }

    fun preferredLocale(language: String, text: String): Locale =
        HopeSpeechLocale.preferredLocale(language, text)

    private fun configureDefaultLanguage(context: Context) {
        val language = try {
            (context.applicationContext as JarvisApp).preferences.language
        } catch (_: Exception) {
            "Auto"
        }
        configureLanguage(engine, language, "")
    }

    private fun configurePersonaVoice(context: Context) {
        val tts = engine ?: return
        val selectedVoice = try {
            (context.applicationContext as JarvisApp).preferences.voice
        } catch (_: Exception) {
            return
        }
        val voice = tts.voices.orEmpty().firstOrNull {
            it.name.equals(selectedVoice, ignoreCase = true) ||
                it.name.contains(selectedVoice, ignoreCase = true)
        }
        if (voice != null) {
            tts.voice = voice
        } else {
            val pitch = when (selectedVoice.lowercase(Locale.ROOT)) {
                "aoede", "kore" -> 1.08f
                "charon", "fenrir" -> 0.92f
                else -> 1f
            }
            tts.setPitch(pitch)
            Log.i(TAG, "Configured Gemini Live persona voice is not installed in local TTS; using the shared device speech voice.")
        }
    }

    private fun configureLanguage(tts: TextToSpeech?, language: String, text: String): Boolean {
        val engine = tts ?: return false
        val locale = preferredLocale(language, text)
        if (engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) return true
        if (locale != Locale.US && engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE) return true
        return false
    }

    private fun speakNext() {
        if (!ready || activeUtteranceId != null) return
        val request = queue.pollFirst() ?: return
        if (request.requireAudioFocus && !requestAudioFocus()) {
            request.onComplete(false)
            speakNext()
            return
        }
        val tts = engine
        if (tts == null || !configureLanguage(tts, request.language, request.text)) {
            request.onComplete(false)
            speakNext()
            return
        }
        activeUtteranceId = request.utteranceId
        callbacks[request.utteranceId] = request.onComplete
        val result = tts.speak(
            request.text,
            TextToSpeech.QUEUE_ADD,
            null,
            request.utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            finishUtterance(request.utteranceId, false)
        }
    }

    private fun finishUtterance(utteranceId: String?, success: Boolean) {
        synchronized(lock) {
            if (utteranceId == null) return
            callbacks.remove(utteranceId)?.let { it(success) }
            if (activeUtteranceId == utteranceId) {
                activeUtteranceId = null
                abandonAudioFocus()
                speakNext()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val context = JarvisApp.instance
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            focusHeld = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focusHeld = manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return focusHeld
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        val manager = audioManager
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(manager::abandonAudioFocusRequest)
        } else if (manager != null) {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
        focusHeld = false
        focusRequest = null
    }

    private fun failQueuedRequests() {
        while (queue.isNotEmpty()) {
            queue.removeFirst().onComplete(false)
        }
    }
}

object HopeSpeechLocale {
    fun preferredLocale(language: String, text: String): Locale = when (language) {
        "Hindi", "Hinglish" -> Locale("hi", "IN")
        "English" -> Locale.US
        else -> if (
            Regex("""[\u0900-\u097F]""").containsMatchIn(text) ||
            Locale.getDefault().language.equals("hi", ignoreCase = true)
        ) {
            Locale("hi", "IN")
        } else {
            Locale.US
        }
    }
}
