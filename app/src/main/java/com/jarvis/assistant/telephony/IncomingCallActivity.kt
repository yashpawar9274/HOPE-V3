package com.jarvis.assistant.telephony

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.HopeSpeech
import java.util.Locale

class IncomingCallActivity : AppCompatActivity() {
    private lateinit var callId: String
    private lateinit var callerView: TextView
    private lateinit var stateView: TextView
    private lateinit var screenTitleView: TextView
    private lateinit var numberView: TextView
    private lateinit var timerView: TextView
    private lateinit var standbyView: TextView
    private lateinit var instructionView: TextView
    private lateinit var quickTextButton: Button
    private lateinit var actions: GridLayout
    private lateinit var endCallButton: Button
    private lateinit var callContent: LinearLayout
    private lateinit var callActionDock: LinearLayout
    private lateinit var callDetails: LinearLayout
    private lateinit var callOrbContainer: FrameLayout
    private lateinit var orbWebView: WebView
    private var orbLoaded = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var promptStarted = false
    private var speechStarted = false
    private var ignoreRecognitionCallback = false
    private var pendingVoiceStart = false
    private var lastCallSnapshot: HopeCallSnapshot? = null
    private var disconnectFinishScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callTimerTick = object : Runnable {
        override fun run() {
            if (::timerView.isInitialized) {
                val call = HopeCallController.currentCall(this@IncomingCallActivity, callId)
                timerView.text = call?.let(::formatCallDuration) ?: "00:00"
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val recognitionTimeout = Runnable {
        if (speechStarted) {
            speechStarted = false
            speechRecognizer?.cancel()
            instructionView.text = "No voice command heard. The call is still ringing; tap Answer or Reject."
        }
    }

    private fun stopVoiceRecognition() {
        if (!speechStarted) return
        speechStarted = false
        updateOrbState()
        ignoreRecognitionCallback = true
        mainHandler.removeCallbacks(recognitionTimeout)
        speechRecognizer?.cancel()
    }

    private val callUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(HopeCallController.EXTRA_CALL_ID) != callId) return
            if (intent.getBooleanExtra("call_removed", false)) {
                showDisconnected(lastCallSnapshot)
            } else {
                refreshCall()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        callId = intent.getStringExtra(HopeCallController.EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) {
            finish()
            return
        }
        createCallUi()
        promptStarted = savedInstanceState?.getBoolean(STATE_PROMPT_STARTED) ?: false
        refreshCall(allowPrompt = !promptStarted)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PROMPT_STARTED, promptStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newCallId = intent.getStringExtra(HopeCallController.EXTRA_CALL_ID)
        if (newCallId.isNullOrBlank()) return
        callId = newCallId
        lastCallSnapshot = null
        disconnectFinishScheduled = false
        mainHandler.removeCallbacks(disconnectFinish)
        promptStarted = false
        refreshCall(allowPrompt = true)
    }

    override fun onResume() {
        super.onResume()
        if (::orbWebView.isInitialized) orbWebView.onResume()
        val filter = IntentFilter(HopeCallController.ACTION_CALL_CHANGED)
        ContextCompat.registerReceiver(this, callUpdates, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshCall(allowPrompt = !promptStarted)
        mainHandler.removeCallbacks(callTimerTick)
        mainHandler.post(callTimerTick)
        if (pendingVoiceStart) {
            pendingVoiceStart = false
            startVoiceRecognition()
        }
    }

    override fun onPause() {
        stopVoiceRecognition()
        mainHandler.removeCallbacks(callTimerTick)
        if (::orbWebView.isInitialized) orbWebView.onPause()
        try {
            unregisterReceiver(callUpdates)
        } catch (_: IllegalArgumentException) {
            // The activity can pause before it has resumed and registered the receiver.
        }
        super.onPause()
    }

    private fun createCallUi() {
        val foreground = Color.rgb(232, 235, 246)
        val accent = Color.rgb(0, 225, 235)
        val muted = Color.rgb(162, 174, 194)
        val root = FrameLayout(this).apply {
            setBackground(
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(24, 15, 51), Color.rgb(15, 20, 39), Color.rgb(10, 15, 27))
                )
            )
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        window.statusBarColor = Color.rgb(20, 14, 42)
        window.navigationBarColor = Color.rgb(10, 15, 27)
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        callContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
        }
        val content = callContent
        screenTitleView = TextView(this).apply {
            text = "Incoming call"
            textSize = 1f
            gravity = Gravity.CENTER
            setTextColor(muted)
            visibility = View.GONE
        }
        content.addView(screenTitleView, matchWidth())
        stateView = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = roundedBackground(Color.rgb(38, 35, 57), dp(24))
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        content.addView(stateView, matchWidth().apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
            topMargin = dp(4)
            bottomMargin = dp(8)
        })
        timerView = TextView(this).apply {
            text = "00:00"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            background = roundedBackground(Color.rgb(30, 34, 51), dp(18))
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        content.addView(timerView, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(4)
        })
        timerView.visibility = View.GONE
        callOrbContainer = FrameLayout(this).apply {
            minimumHeight = dp(300)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        orbWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        orbWebView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                orbLoaded = true
                updateOrbState()
            }
        }
        orbWebView.loadUrl("https://appassets.androidplatform.net/assets/call_orb.html?embedded=1")
        callOrbContainer.addView(orbWebView, FrameLayout.LayoutParams(-1, -1))
        callDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(4), 0, 0)
        }
        callerView = TextView(this).apply {
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(2), 0, dp(2))
        }
        callDetails.addView(callerView, matchWidth())
        numberView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(5))
        }
        callDetails.addView(numberView, matchWidth())
        content.addView(callOrbContainer, LinearLayout.LayoutParams(-1, dp(330)).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })
        content.addView(callDetails, matchWidth())
        val orbHeight = (resources.configuration.screenHeightDp * 0.30f).toInt().coerceIn(180, 310)
        callOrbContainer.minimumHeight = dp(orbHeight)
        callOrbContainer.layoutParams =
            (callOrbContainer.layoutParams as LinearLayout.LayoutParams).apply { height = dp(orbHeight) }
        standbyView = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            background = roundedBackground(Color.rgb(25, 29, 43), dp(14))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(standbyView, matchWidth().apply {
            topMargin = dp(4)
            bottomMargin = dp(5)
        })
        instructionView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            background = roundedBackground(Color.rgb(27, 31, 44), dp(14))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(instructionView, matchWidth().apply {
            topMargin = dp(3)
            bottomMargin = dp(8)
        })
        quickTextButton = Button(this).apply {
            text = "Quick text"
            isAllCaps = false
            textSize = 15f
            setTextColor(foreground)
            background = roundedBackground(Color.rgb(39, 43, 60), dp(24))
            visibility = View.GONE
        }
        content.addView(quickTextButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(36)
            rightMargin = dp(36)
            bottomMargin = dp(4)
        })
        actions = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
        }
        endCallButton = Button(this).apply {
            text = "End call"
            textSize = 17f
            isAllCaps = false
            contentDescription = "End call"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(177, 0, 24), dp(30))
        }
        callActionDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.rgb(11, 15, 28), Color.rgb(11, 15, 28), Color.TRANSPARENT)
            )
            elevation = dp(8).toFloat()
        }
        callActionDock.addView(actions, matchWidth())
        callActionDock.addView(endCallButton, LinearLayout.LayoutParams(-1, dp(64)).apply {
            topMargin = dp(6)
            leftMargin = dp(42)
            rightMargin = dp(42)
            bottomMargin = dp(3)
        })
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            callActionDock,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        )
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun refreshCall(allowPrompt: Boolean = false) {
        val call = HopeCallController.currentCall(this, callId)
        if (call == null) {
            showDisconnected(lastCallSnapshot)
            return
        }
        lastCallSnapshot = call
        if (call.state == android.telecom.Call.STATE_DISCONNECTED) {
            showDisconnected(call)
            return
        }
        disconnectFinishScheduled = false
        mainHandler.removeCallbacks(disconnectFinish)
        callId = call.id
        callerView.text = call.caller
        numberView.text = call.number.takeIf(String::isNotBlank) ?: "Number unavailable"
        timerView.text = formatCallDuration(call)
        val ringing = call.state == android.telecom.Call.STATE_RINGING
        val activeOrHeld = call.state == android.telecom.Call.STATE_ACTIVE ||
            call.state == android.telecom.Call.STATE_HOLDING
        val dialing = call.state == android.telecom.Call.STATE_DIALING ||
            call.state == android.telecom.Call.STATE_CONNECTING
        val orbHeight = (
            resources.configuration.screenHeightDp * if (ringing) 0.30f else 0.26f
        ).toInt().coerceIn(170, 310)
        callOrbContainer.minimumHeight = dp(orbHeight)
        callOrbContainer.layoutParams = (callOrbContainer.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(orbHeight)
        }
        screenTitleView.text = when (call.state) {
            android.telecom.Call.STATE_RINGING -> "Incoming call"
            android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING -> "Outgoing call"
            android.telecom.Call.STATE_HOLDING -> "Call on hold"
            android.telecom.Call.STATE_ACTIVE -> "Active call"
            else -> "Call"
        }
        stateView.text = "${callStateLabel(call.state).uppercase(Locale.ROOT)}   |   ANDROID TELECOM"
        timerView.visibility = if (
            activeOrHeld
        ) View.VISIBLE else View.GONE
        moveCallDetails(ringing)
        callContent.setPadding(
            dp(20),
            dp(14),
            dp(20),
            dp(if (ringing) 120 else if (activeOrHeld) 240 else if (dialing) 150 else 100)
        )
        val orbState = when (call.state) {
            android.telecom.Call.STATE_RINGING -> if (speechStarted) "listening" else "ringing"
            android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING -> "thinking"
            android.telecom.Call.STATE_ACTIVE -> "active"
            android.telecom.Call.STATE_HOLDING -> "idle"
            else -> "idle"
        }
        standbyView.text = if (ringing) {
            when {
                IncomingCallPreferences.isVoiceConfirmationEnabled(this) ->
                    "Voice confirmation enabled • use Answer / Decline anytime"
                IncomingCallPreferences.isAnnouncementEnabled(this) ->
                    "Caller announcement enabled • call audio is not accessed"
                else -> "Voice controls are off • call audio is not accessed"
            }
        } else {
            when (call.state) {
                android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING ->
                    "Calling through Android Telecom • waiting for call state"
                android.telecom.Call.STATE_HOLDING -> "Call is held by Android Telecom"
                else -> "Connected through Android Telecom"
            }
        }
        endCallButton.visibility = if (ringing) View.GONE else View.VISIBLE
        callActionDock.visibility = View.VISIBLE
        quickTextButton.visibility = if (ringing && call.number.isNotBlank()) View.VISIBLE else View.GONE
        quickTextButton.setOnClickListener {
            if (call.number.isBlank()) return@setOnClickListener
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_SENDTO,
                        android.net.Uri.fromParts("smsto", call.number, null)
                    )
                )
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Could not open the SMS composer for the incoming caller.", error)
                Toast.makeText(this, "No messaging app is available.", Toast.LENGTH_SHORT).show()
            }
        }
        endCallButton.setOnClickListener {
            if (!HopeCallController.hangUp(callId)) showCallActionFailure()
        }
        if (!ringing) {
            HopeSpeech.stop(PROMPT_UTTERANCE)
            stopVoiceRecognition()
        }
        actions.removeAllViews()
        actions.visibility = View.VISIBLE
        actions.columnCount = if (ringing) 2 else 3
        if (ringing) {
            instructionView.text = if (IncomingCallPreferences.isVoiceConfirmationEnabled(this)) {
                "Say “answer” or “decline”, or tap a button."
            } else {
                "Choose whether to answer or decline."
            }
            addAction("Decline", R.color.status_red, "Decline incoming call") {
                if (!HopeCallController.reject(callId)) showCallActionFailure()
            }
            addAction("Answer", R.color.status_green, "Answer incoming call") {
                if (!HopeCallController.answer(callId)) showCallActionFailure()
            }
            if (allowPrompt && !promptStarted &&
                (IncomingCallPreferences.isAnnouncementEnabled(this) ||
                    IncomingCallPreferences.isVoiceConfirmationEnabled(this))
            ) {
                promptStarted = true
                speakCallPrompt(call.caller)
            }
        } else if (activeOrHeld) {
            instructionView.text = "Cellular call via Android Telecom. HOPE does not record or transcribe call audio."
            addAction(if (call.muted) "Unmute" else "Mute", R.color.accent_sapphire, "Toggle microphone mute") {
                if (!HopeCallController.toggleMute()) showCallActionFailure()
            }
            addAction("Keypad", R.color.accent_sapphire, "Open in-call keypad") {
                showKeypad()
            }
            addAction(if (call.speakerOn) "Speaker on" else "Speaker", R.color.accent_sapphire, "Toggle speakerphone") {
                if (!call.canSpeaker || !HopeCallController.toggleSpeaker()) showCallActionFailure()
            }
            val holding = call.state == android.telecom.Call.STATE_HOLDING
            addAction(
                if (!call.canHold) "Hold unavailable" else if (holding) "Resume" else "Hold",
                R.color.accent_sapphire,
                if (!call.canHold) "Hold is not supported by this call" else "Hold or resume call"
            ) {
                if (!call.canHold || !HopeCallController.toggleHold(callId)) showCallActionFailure()
            }
            addAction("Add call", R.color.accent_sapphire, "Start another call") {
                if (!HopeCallController.addCall(this, callId)) showCallActionFailure()
            }
            addAction("Recording unavailable", R.color.status_amber, "Call recording unavailable") {
                showRecordingUnavailable()
            }
            if (call.canMerge) {
                addAction("Merge", R.color.accent_sapphire, "Merge supported calls into conference") {
                    if (!HopeCallController.mergeCalls(callId)) showCallActionFailure()
                }
            }
        } else if (dialing) {
            instructionView.text = "Calling through Android Telecom. Audio controls are ready; keypad tones are available after connection."
            addAction("Mute", R.color.accent_sapphire, "Toggle microphone mute") {
                if (!HopeCallController.toggleMute()) showCallActionFailure()
            }
            addAction(
                if (call.canSpeaker) "Speaker" else "Speaker unavailable",
                R.color.accent_sapphire,
                "Toggle speakerphone",
                enabled = call.canSpeaker
            ) {
                if (!HopeCallController.toggleSpeaker()) showCallActionFailure()
            }
            addAction(
                "Keypad",
                R.color.accent_sapphire,
                "Keypad tones are available after the call connects",
                enabled = false
            ) { }
        } else {
            actions.visibility = View.GONE
            instructionView.text = "Waiting for Android Telecom to connect or disconnect the call."
        }
        updateOrbState(orbState)
    }

    private val disconnectFinish = Runnable {
        if (disconnectFinishScheduled && !isFinishing) finish()
    }

    private fun showDisconnected(call: HopeCallSnapshot?) {
        if (call == null && lastCallSnapshot == null) {
            finish()
            return
        }
        val endedCall = call ?: lastCallSnapshot!!
        lastCallSnapshot = endedCall
        callerView.text = endedCall.caller
        numberView.text = endedCall.number.takeIf(String::isNotBlank) ?: "Number unavailable"
        screenTitleView.text = "Call ended"
        stateView.text = "CALL ENDED   |   ANDROID TELECOM"
        standbyView.text = "The call has ended."
        instructionView.text = "Returning to HOPE Phone…"
        timerView.visibility = View.GONE
        quickTextButton.visibility = View.GONE
        endCallButton.visibility = View.GONE
        callActionDock.visibility = View.GONE
        actions.removeAllViews()
        actions.visibility = View.GONE
        updateOrbState("disconnected")
        if (!disconnectFinishScheduled) {
            disconnectFinishScheduled = true
            mainHandler.postDelayed(disconnectFinish, 2_000L)
        }
    }

    private fun moveCallDetails(ringing: Boolean) {
        callContent.removeView(callDetails)
        val orbIndex = callContent.indexOfChild(callOrbContainer)
        val detailIndex = if (ringing) orbIndex + 1 else 0
        callContent.addView(callDetails, detailIndex.coerceAtLeast(0), matchWidth())
    }

    private fun speakCallPrompt(caller: String) {
        val voiceConfirmation = IncomingCallPreferences.isVoiceConfirmationEnabled(this)
        val announcement = IncomingCallPreferences.isAnnouncementEnabled(this)
        val language = IncomingCallPreferences.language(this)
        val hindi = language == "Hindi" ||
            language == "Auto" && Locale.getDefault().language.equals("hi", ignoreCase = true)
        val prompt = if (hindi) {
            if (voiceConfirmation) {
                "Sir, $caller ka call aa raha hai. Kya main call uthaoon ya reject karoon? Uthane ke liye uthao, reject karne ke liye reject boliye."
            } else {
                "Sir, $caller ka call aa raha hai."
            }
        } else {
            if (voiceConfirmation) {
                "Sir, incoming call from $caller. Should I answer or decline? Say answer or decline."
            } else {
                "Sir, incoming call from $caller."
            }
        }
        instructionView.text = if (voiceConfirmation) {
            if (hindi) "Voice command: call uthao ya reject karo." else "Say “answer” or “decline”."
        } else {
            if (announcement) "Caller announcement is enabled." else ""
        }
        if (!HopeSpeech.speak(this, prompt, language, PROMPT_UTTERANCE, queueMode = android.speech.tts.TextToSpeech.QUEUE_FLUSH) { spoken ->
                if (spoken) {
                    onPromptFinished(voiceConfirmation)
                } else {
                    instructionView.text = "HOPE speech is unavailable. Use the on-screen Answer or Reject controls."
                }
            }
        ) {
            instructionView.text = "HOPE speech is unavailable. Use the on-screen Answer or Reject controls."
        }
    }

    private fun onPromptFinished(listenAfterPrompt: Boolean) {
        if (!listenAfterPrompt || speechStarted) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            pendingVoiceStart = true
            return
        }
        if (HopeCallController.incomingCall(this, callId) == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instructionView.text = "Microphone permission is needed for voice controls. Use the on-screen buttons."
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            instructionView.text = "Voice recognition is unavailable. Use Answer or Reject."
            return
        }
        speechStarted = true
        ignoreRecognitionCallback = false
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                instructionView.text = if (IncomingCallPreferences.language(this@IncomingCallActivity) == "Hindi") {
                    "Suniye… call uthao ya reject karo."
                } else {
                    "Listening briefly for “answer” or “reject”…"
                }
                mainHandler.removeCallbacks(recognitionTimeout)
                mainHandler.postDelayed(recognitionTimeout, 8_000L)
                updateOrbState()
            }

            override fun onResults(results: Bundle?) {
                mainHandler.removeCallbacks(recognitionTimeout)
                if (ignoreRecognitionCallback) {
                    ignoreRecognitionCallback = false
                    return
                }
                speechStarted = false
                updateOrbState()
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val command = recognized.firstNotNullOfOrNull(IncomingCallVoiceCommandParser::parse)
                when (command) {
                    IncomingCallVoiceCommand.ANSWER -> {
                        if (!HopeCallController.answer(callId)) showCallActionFailure()
                    }
                    IncomingCallVoiceCommand.REJECT -> {
                        if (!HopeCallController.reject(callId)) showCallActionFailure()
                    }
                    null -> instructionView.text = "No clear command heard. The call is still ringing; tap Answer or Reject."
                }
            }

            override fun onError(error: Int) {
                mainHandler.removeCallbacks(recognitionTimeout)
                if (ignoreRecognitionCallback) {
                    ignoreRecognitionCallback = false
                    return
                }
                speechStarted = false
                updateOrbState()
                instructionView.text = "No voice command recognized. The call is still ringing; tap Answer or Reject."
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
        })
        val language = IncomingCallPreferences.language(this)
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (language) {
                "Hindi" -> "hi-IN"
                "English" -> "en-IN"
                else -> Locale.getDefault().toLanguageTag()
            })
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
        }
        try {
            mainHandler.removeCallbacks(recognitionTimeout)
            mainHandler.postDelayed(recognitionTimeout, 10_000L)
            recognizer.startListening(recognitionIntent)
        } catch (error: Exception) {
            mainHandler.removeCallbacks(recognitionTimeout)
            speechStarted = false
            updateOrbState()
            android.util.Log.e(TAG, "Could not start speech recognition for the incoming call.", error)
            instructionView.text = "Voice recognition could not start. Use Answer or Reject."
        }
    }

    private fun addAction(
        label: String,
        colorRes: Int,
        description: String,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val incomingTile = label == "Decline" || label == "Answer"
        val declineTile = label == "Decline"
        val button = Button(this).apply {
            text = label
            contentDescription = description
            textSize = if (incomingTile) 17f else 13f
            isAllCaps = false
            isEnabled = enabled
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setTextColor(
                if (incomingTile && !declineTile) Color.rgb(0, 34, 38)
                else ContextCompat.getColor(
                    this@IncomingCallActivity,
                    if (declineTile) android.R.color.white else colorRes
                )
            )
            background = roundedBackground(
                when {
                    declineTile -> Color.rgb(157, 0, 20)
                    incomingTile -> Color.rgb(0, 222, 230)
                    else -> Color.rgb(34, 39, 56)
                },
                dp(if (incomingTile) 26 else 18)
            )
            setOnClickListener { onClick() }
        }
        actions.addView(button, GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = dp(if (incomingTile) 82 else 62)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        })
    }

    private fun showKeypad() {
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#').forEach { digit ->
            grid.addView(Button(this).apply {
                text = digit.toString()
                textSize = 22f
                contentDescription = "Send DTMF $digit"
                setOnClickListener {
                    if (!HopeCallController.sendDtmf(callId, digit)) {
                        Toast.makeText(
                            this@IncomingCallActivity,
                            "Android could not send that keypad tone.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(58)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("In-call keypad")
            .setView(grid)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun formatCallDuration(call: HopeCallSnapshot): String {
        if (call.connectTimeMillis <= 0L) return "00:00"
        val seconds = ((System.currentTimeMillis() - call.connectTimeMillis).coerceAtLeast(0L) / 1_000L)
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun updateOrbState(forceState: String? = null) {
        if (!orbLoaded || !::orbWebView.isInitialized) return
        val snapshot = HopeCallController.currentCall(this, callId)
        val state = forceState ?: when (snapshot?.state) {
            android.telecom.Call.STATE_RINGING -> if (speechStarted) "listening" else "ringing"
            android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING -> "thinking"
            android.telecom.Call.STATE_ACTIVE -> "active"
            android.telecom.Call.STATE_HOLDING -> "idle"
            else -> "disconnected"
        }
        orbWebView.evaluateJavascript("window.HopeCallOrb && window.HopeCallOrb.setState('$state');", null)
    }

    private fun roundedBackground(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun callStateLabel(state: Int): String = when (state) {
        android.telecom.Call.STATE_RINGING -> "Incoming call"
        android.telecom.Call.STATE_ACTIVE -> "Active call"
        android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING -> "Outgoing call"
        android.telecom.Call.STATE_HOLDING -> "Call on hold"
        else -> "Call"
    }

    private fun showCallActionFailure() {
        Toast.makeText(this, "Android could not apply that call action.", Toast.LENGTH_SHORT).show()
        refreshCall()
    }

    private fun showRecordingUnavailable() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Call recording unavailable")
            .setMessage(
                "Android does not expose an authorized public API for HOPE to record cellular-call audio on this device. Recording will remain disabled rather than using a workaround. Use only a lawful, platform-supported recording method that clearly informs everyone on the call."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::orbWebView.isInitialized) {
            orbWebView.stopLoading()
            orbWebView.destroy()
        }
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        mainHandler.removeCallbacks(recognitionTimeout)
        HopeSpeech.stop(PROMPT_UTTERANCE)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "IncomingCallActivity"
        const val STATE_PROMPT_STARTED = "prompt_started"
        const val PROMPT_UTTERANCE = "incoming-call-prompt"
    }
}
