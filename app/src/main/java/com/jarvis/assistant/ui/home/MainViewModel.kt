package com.jarvis.assistant.ui.home

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiRouter
import com.jarvis.assistant.ai.CriticalThinkingInstructions
import com.jarvis.assistant.ai.CapabilityRegistry
import com.jarvis.assistant.ai.VoicePersonaRegistry
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.autoreply.AutoReplyCoordinator
import com.jarvis.assistant.autoreply.WhatsAppAutoReplyCommandPolicy
import com.jarvis.assistant.autoreply.WhatsAppAutoReplyControl
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.LocationQuestionPolicy
import com.jarvis.assistant.util.LocationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This ViewModel is intentionally "thin": the microphone, the Gemini Live
 * socket, and the standby/wake-word listener all live inside
 * [JarvisConversationService], not here. This class only mirrors that
 * service's state for the UI and forwards user taps as control intents.
 *
 * That split matters: when the app is removed from Recents, Android destroys
 * the Activity and this ViewModel - if the live conversation lived here, it
 * would die with it. Because it lives in the foreground service instead, the
 * conversation (or standby listening) keeps running untouched, and whenever
 * the UI comes back it just resumes observing whatever the service is doing.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: AppPreferences = (application as JarvisApp).preferences
    private val chatRepository = (application as JarvisApp).chatRepository
    private val aiRouter: AiRouter = (application as JarvisApp).aiRouter

    val isSessionOn: StateFlow<Boolean> = JarvisConversationService.isSessionOn
    val conversationState: StateFlow<ConversationState> = JarvisConversationService.conversationState
    val audioLevel: StateFlow<Float> = JarvisConversationService.audioLevel
    val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = JarvisConversationService.audioFrequency
    val liveUserTranscript: StateFlow<String> = JarvisConversationService.liveUserTranscript
    val liveAssistantResponse: StateFlow<String> = JarvisConversationService.liveAssistantResponse
    val liveError: StateFlow<com.jarvis.assistant.background.JarvisLiveError?> = JarvisConversationService.liveError
    val chatTurns: StateFlow<List<ChatTurn>> = chatRepository.turns

    fun clearLiveError() {
        JarvisConversationService.clearLiveError()
    }

    private val _isMicMuted = MutableStateFlow(preferences.isMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Surfaces "STANDBY" whenever the service is listening only for the wake
    // word, otherwise mirrors the service's live connection status.
    private val _connectionStatus = MutableStateFlow("READY")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _liveTime = MutableStateFlow("")
    val liveTime: StateFlow<String> = _liveTime.asStateFlow()

    private val _personalityName = MutableStateFlow(preferences.personality)
    val personalityName: StateFlow<String> = _personalityName.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    private var timeClockJob: Job? = null
    private var chatGenerationJob: Job? = null
    private val _isGeneratingChatReply = MutableStateFlow(false)
    val isGeneratingChatReply: StateFlow<Boolean> = _isGeneratingChatReply.asStateFlow()
    private val _chatErrors = MutableSharedFlow<String>()
    val chatErrors: SharedFlow<String> = _chatErrors.asSharedFlow()

    init {
        startTimeClock()
        observeServiceStatus()
        observeServiceEvents()
    }

    private fun startTimeClock() {
        timeClockJob = viewModelScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) {
                _liveTime.value = sdf.format(Date())
                delay(1000)
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            combine(
                JarvisConversationService.isStandby,
                JarvisConversationService.connectionStatus
            ) { standby, liveStatus -> if (standby) "STANDBY" else liveStatus }
                .collect { _connectionStatus.value = it }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            JarvisConversationService.events.collect { message -> _eventFlow.emit(message) }
        }
        viewModelScope.launch {
            JarvisConversationService.wakeEvents.collect {
                _eventFlow.emit("Wake word detected")
            }
        }
    }

    fun refreshSettings() {
        _personalityName.value = preferences.personality
        _isMicMuted.value = preferences.isMicMuted
        JarvisConversationService.setMicMuted(preferences.isMicMuted)
    }

    fun sendChatMessage(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _isGeneratingChatReply.value) return
        chatGenerationJob = viewModelScope.launch {
            _isGeneratingChatReply.value = true
            try {
                val autoReplyCommand = WhatsAppAutoReplyCommandPolicy.detect(prompt)
                if (autoReplyCommand != null) {
                    val result = WhatsAppAutoReplyControl.execute(getApplication(), autoReplyCommand)
                    chatRepository.addTurn(prompt, result.message, "On-device WhatsApp auto-reply control")
                    return@launch
                }
                val context = chatRepository.turns.value.takeLast(8).joinToString("\n") { turn ->
                    "User: ${turn.userTranscript}\nHOPE: ${turn.jarvisResponse}"
                }
                val personaTaskContext = "$context\n$prompt"
                val voice = preferences.voice
                if (LocationQuestionPolicy.isCurrentLocationQuestion(prompt)) {
                    val localReply = if (!preferences.isLocationAwarenessEnabled) {
                        "Location awareness is off. Enable it in Settings > Location Awareness & Privacy to request a one-shot location fix."
                    } else {
                        val snapshot = LocationRepository(getApplication())
                            .getCurrentLocation(preferences.isLocationGeocodingEnabled)
                        val approximate = snapshot.locality
                        if (approximate.isNullOrBlank()) {
                            "I obtained a fresh device location fix, but cannot identify the area name because external geocoding is off or unavailable. Enable external geocoding in Settings > Location Awareness & Privacy to get a named locality. I won't guess."
                        } else {
                            val precision = if (!snapshot.hasKnownAccuracy) {
                                " Reported accuracy is unavailable."
                            } else if (snapshot.isPoorAccuracy) {
                                " The reported accuracy is limited (${snapshot.accuracyMeters.toInt()} metres)."
                            } else {
                                " Accuracy is about ${snapshot.accuracyMeters.toInt()} metres."
                            }
                            val coordinates = if (prompt.contains("coordinate", ignoreCase = true)) {
                                " Coordinates: ${snapshot.latitude}, ${snapshot.longitude}."
                            } else {
                                ""
                            }
                            "Your approximate area is $approximate.$precision Location was obtained at ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.updatedAtMillis))}.$coordinates"
                        }
                    }
                    chatRepository.addTurn(prompt, localReply, "On-device location")
                    return@launch
                }
                val locationRelevant = LocationQuestionPolicy.isLocationRelevant(prompt)
                val locationGuidance = if (locationRelevant) {
                    if (!preferences.isLocationAwarenessEnabled || !preferences.isLocationSharingEnabled) {
                        "The user's location has not been shared with Gemini for this request. Do not infer or disclose their location; explain that location sharing is off if needed."
                    } else {
                        try {
                            "For this location-relevant request, use this fresh approximate location snapshot only: ${
                                LocationRepository(getApplication())
                                    .getCurrentLocation(preferences.isLocationGeocodingEnabled)
                                    .sharedContext()
                            } Coordinates are not included."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            "A fresh location could not be obtained for this request (${error.message ?: "location unavailable"}). Do not infer location from old memories or claim a current location."
                        }
                    }
                } else {
                    ""
                }
                val result = aiRouter.generate(
                    mode = preferences.aiMode,
                    systemInstruction = listOf(
                        CriticalThinkingInstructions.forText(
                            preferences.aiSystemInstruction,
                            preferences.isCriticalThinkingEnabled
                        ),
                        locationGuidance,
                        CapabilityRegistry.promptGuidance(),
                        VoicePersonaRegistry.promptFor(
                            selectedVoice = voice,
                            task = personaTaskContext,
                            automaticSwitchingEnabled = preferences.isAutomaticPersonaSwitchingEnabled,
                            customName = preferences.getVoicePersonaCustomization(voice, "name"),
                            customExpertise = preferences.getVoicePersonaCustomization(voice, "expertise"),
                            customTone = preferences.getVoicePersonaCustomization(voice, "tone")
                        ),
                        if (preferences.isAutomaticPersonaSwitchingEnabled) VoicePersonaRegistry.routingGuide() else ""
                    ).filter(String::isNotBlank).joinToString("\n\n"),
                    context = context,
                    prompt = prompt
                )
                chatRepository.addTurn(prompt, result.text, result.engine.displayName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _chatErrors.emit(error.message ?: "AI response failed.")
            } finally {
                _isGeneratingChatReply.value = false
            }
        }
    }

    fun cancelChatGeneration() {
        chatGenerationJob?.cancel()
        chatGenerationJob = null
    }

    fun turnOffSession() {
        sendServiceAction(JarvisConversationService.ACTION_STOP_ALL)
        viewModelScope.launch {
            _eventFlow.emit("HOPE Powered Off")
        }
    }

    fun toggleSession() {
        if (JarvisConversationService.isSessionOn.value) {
            turnOffSession()
        } else {
            startSession()
        }
    }

    fun ensureSessionOn() {
        if (!JarvisConversationService.isSessionOn.value) startSession()
    }

    private fun startSession() {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasAllRequiredPermissions(context)) {
            viewModelScope.launch {
                _eventFlow.emit("Please allow all permissions in Settings to activate HOPE.")
            }
            return
        }
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            viewModelScope.launch {
                _eventFlow.emit("Please configure your Gemini API Key in Settings first.")
            }
            return
        }
        sendServiceAction(JarvisConversationService.ACTION_START_CONVERSATION)
    }

    fun toggleMicMute() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        preferences.isMicMuted = newMuted
        JarvisConversationService.setMicMuted(newMuted)
        viewModelScope.launch {
            _eventFlow.emit(if (newMuted) "Microphone Muted (Off)" else "Microphone Active (On)")
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(getApplication<Application>(), JarvisConversationService::class.java)
            .setAction(action)
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
    }

    override fun onCleared() {
        super.onCleared()
        timeClockJob?.cancel()
        // Deliberately NOT stopping JarvisConversationService here. The
        // Activity/ViewModel being torn down (e.g. the app swiped away from
        // Recents) must not stop the mic or the live conversation - that's
        // the whole point of owning them in the service instead of here.
    }
}
