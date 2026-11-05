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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var quickTextButton: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var callRoot: FrameLayout
    private lateinit var bgImage: ImageView
    private lateinit var orbContainer: FrameLayout
    private lateinit var orbView: HopeOrbView
    private val pendingActions = ArrayList<View>()
    private val proximityLock by lazy { CallProximityLock(this) }
    private var backgroundKey: String? = null
    private val compactScreen: Boolean get() = resources.configuration.screenHeightDp < 700
    private val actionSizeDp: Int get() = if (compactScreen) 64 else 72
    private lateinit var incomingBar: LinearLayout
    private lateinit var endCallButton: LinearLayout
    private lateinit var listeningView: TextView
    private lateinit var callContent: LinearLayout
    private lateinit var callActionDock: LinearLayout
    private lateinit var callDetails: LinearLayout
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
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
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
        // Screen goes back to normal behaviour when the call screen is not in front.
        proximityLock.release()
        stopVoiceRecognition()
        mainHandler.removeCallbacks(callTimerTick)
        try {
            unregisterReceiver(callUpdates)
        } catch (_: IllegalArgumentException) {
            // The activity can pause before it has resumed and registered the receiver.
        }
        super.onPause()
    }

    private fun createCallUi() {
        val white = Color.WHITE
        val soft = Color.argb(200, 255, 255, 255)
        val faint = Color.argb(150, 255, 255, 255)
        val compact = compactScreen
        val root = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                CallerBackground.gradientFor("hope")
            )
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        callRoot = root
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Caller photo (blurred) sits under a dark scrim so text and buttons stay readable.
        bgImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            alpha = 0f
        }
        root.addView(bgImage, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(190, 0, 0, 0))
                )
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        callContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(if (compact) 36 else 52), dp(24), dp(16))
        }
        val content = callContent
        screenTitleView = TextView(this).apply { visibility = View.GONE }
        content.addView(screenTitleView, LinearLayout.LayoutParams(0, 0))
        callDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        callerView = TextView(this).apply {
            textSize = if (compact) 30f else 34f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0))
        }
        callDetails.addView(callerView, matchWidth())
        stateView = TextView(this).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(soft)
            setPadding(0, dp(4), 0, 0)
        }
        callDetails.addView(stateView, matchWidth())
        timerView = TextView(this).apply {
            text = "00:00"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(soft)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        callDetails.addView(timerView, matchWidth())
        numberView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(faint)
            setPadding(0, dp(6), 0, 0)
        }
        callDetails.addView(numberView, matchWidth())
        content.addView(callDetails, matchWidth())

        // HOPE ORB in the centre of every call screen (incoming, outgoing, active, on hold).
        orbContainer = FrameLayout(this).apply { clipToPadding = false }
        orbView = HopeOrbView(this)
        orbContainer.addView(orbView, FrameLayout.LayoutParams(-1, -1))
        content.addView(orbContainer, LinearLayout.LayoutParams(-1, dp(if (compact) 160 else 260)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        listeningView = TextView(this).apply {
            text = "\u25CF  HOPE is listening\u2026"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosCallStyle.GREEN)
            visibility = View.GONE
        }
        content.addView(listeningView, matchWidth().apply { bottomMargin = dp(4) })
        standbyView = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(faint)
        }
        content.addView(standbyView, matchWidth().apply { bottomMargin = dp(2) })
        instructionView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(soft)
        }
        content.addView(instructionView, matchWidth().apply { bottomMargin = dp(8) })

        // iPhone "Message" shortcut shown above Decline / Accept.
        quickTextButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedBackground(Color.argb(70, 255, 255, 255), dp(22))
            isClickable = true
            isFocusable = true
            contentDescription = "Reply with a message"
            visibility = View.GONE
            addView(IosCallStyle.icon(this@IncomingCallActivity, R.drawable.ic_ios_message, white, 18))
            addView(TextView(this@IncomingCallActivity).apply {
                text = "Message"
                textSize = 15f
                setTextColor(white)
                setPadding(dp(8), 0, 0, 0)
            })
        }
        content.addView(quickTextButton, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        incomingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        endCallButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "End call"
            background = IosCallStyle.rippleCircle(IosCallStyle.RED)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(IosCallStyle.icon(this@IncomingCallActivity, R.drawable.ic_ios_call_end, white, 34))
        }
        callActionDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        callActionDock.addView(incomingBar, matchWidth())
        callActionDock.addView(actions, matchWidth())
        val endSize = if (compact) 68 else 76
        callActionDock.addView(endCallButton, LinearLayout.LayoutParams(dp(endSize), dp(endSize)).apply {
            topMargin = dp(if (compact) 8 else 14)
            bottomMargin = dp(6)
        })
        content.addView(callActionDock, matchWidth())

        // Scrolls only on very small screens, so the buttons can never be pushed off the display.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
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

    /** Orb takes the free space between the caller name and the buttons, never less than 120dp. */
    private fun applyOrbHeight(dockDp: Int) {
        if (!::orbContainer.isInitialized) return
        val usedAboveAndBelow = (if (compactScreen) 36 else 52) + 110 + 40 + dockDp + 16 + 24
        val orbDp = (resources.configuration.screenHeightDp - usedAboveAndBelow).coerceIn(120, 340)
        val params = orbContainer.layoutParams as LinearLayout.LayoutParams
        if (params.height != dp(orbDp)) {
            params.height = dp(orbDp)
            orbContainer.layoutParams = params
        }
    }

    private fun applyCallerBackground(call: HopeCallSnapshot) {
        val key = call.number.ifBlank { call.caller }
        if (key == backgroundKey) return
        backgroundKey = key
        callRoot.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            CallerBackground.gradientFor(call.caller)
        )
        bgImage.visibility = View.GONE
        bgImage.alpha = 0f
        if (call.number.isBlank()) return
        val metrics = resources.displayMetrics
        Thread {
            val photo = CallerBackground.loadBlurredPhoto(
                applicationContext,
                call.number,
                metrics.widthPixels,
                metrics.heightPixels
            )
            if (photo != null) {
                runOnUiThread {
                    if (!isDestroyed && key == backgroundKey) {
                        bgImage.setImageBitmap(photo)
                        bgImage.visibility = View.VISIBLE
                        bgImage.animate().alpha(1f).setDuration(450).start()
                    }
                }
            }
        }.start()
    }

    /**
     * Earpiece call: screen turns off near the ear (proximity lock) and is not forced on.
     * Speaker / headset / ringing: screen stays on so the buttons can be used.
     */
    private fun applyScreenPolicy(call: HopeCallSnapshot) {
        val inCall = call.state != android.telecom.Call.STATE_RINGING &&
            call.state != android.telecom.Call.STATE_DISCONNECTED
        val awayFromEar = call.speakerOn || CallProximityLock.isHeadsetConnected(this)
        val atEar = inCall && !awayFromEar
        proximityLock.setEnabled(atEar)
        if (atEar) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun flushActions() {
        actions.removeAllViews()
        pendingActions.chunked(3).forEach { rowViews ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowViews.forEach { view ->
                val cell = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    addView(view)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            repeat(3 - rowViews.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            actions.addView(row, matchWidth().apply {
                topMargin = dp(if (compactScreen) 4 else 8)
                bottomMargin = dp(if (compactScreen) 4 else 8)
            })
        }
        pendingActions.clear()
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
        val shownNumber = call.number.takeIf(String::isNotBlank)
        numberView.text = if (shownNumber != null && shownNumber != call.caller) shownNumber else ""
        numberView.visibility = if (numberView.text.isEmpty()) View.GONE else View.VISIBLE
        timerView.text = formatCallDuration(call)
        val ringing = call.state == android.telecom.Call.STATE_RINGING
        val holding = call.state == android.telecom.Call.STATE_HOLDING
        val activeOrHeld = call.state == android.telecom.Call.STATE_ACTIVE || holding
        val dialing = call.state == android.telecom.Call.STATE_DIALING ||
            call.state == android.telecom.Call.STATE_CONNECTING
        screenTitleView.text = when (call.state) {
            android.telecom.Call.STATE_RINGING -> "Incoming call"
            android.telecom.Call.STATE_DIALING, android.telecom.Call.STATE_CONNECTING -> "Outgoing call"
            android.telecom.Call.STATE_HOLDING -> "Call on hold"
            android.telecom.Call.STATE_ACTIVE -> "Active call"
            else -> "Call"
        }
        // iPhone wording: "mobile" while ringing, "calling…" while dialing, the timer once connected.
        stateView.text = when {
            ringing -> "mobile"
            dialing -> "calling\u2026"
            holding -> "on hold"
            else -> ""
        }
        stateView.visibility = if (stateView.text.isEmpty()) View.GONE else View.VISIBLE
        timerView.visibility = if (activeOrHeld) View.VISIBLE else View.GONE
        standbyView.text = if (ringing) {
            when {
                IncomingCallPreferences.isVoiceConfirmationEnabled(this) ->
                    "Voice confirmation on \u2022 say \u201Canswer\u201D or \u201Cdecline\u201D"
                IncomingCallPreferences.isAnnouncementEnabled(this) ->
                    "HOPE announces the caller \u2022 call audio is not accessed"
                else -> ""
            }
        } else ""
        quickTextButton.visibility = if (ringing && call.number.isNotBlank()) View.VISIBLE else View.GONE
        quickTextButton.setOnClickListener {
            if (call.number.isBlank()) return@setOnClickListener
            try {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", call.number, null))
                )
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Could not open the SMS composer for the incoming caller.", error)
                Toast.makeText(this, "No messaging app is available.", Toast.LENGTH_SHORT).show()
            }
        }
        endCallButton.visibility = if (ringing) View.GONE else View.VISIBLE
        endCallButton.setOnClickListener {
            if (!HopeCallController.hangUp(callId)) showCallActionFailure()
        }
        if (!ringing) {
            HopeSpeech.stop(PROMPT_UTTERANCE)
            stopVoiceRecognition()
        }
        callActionDock.visibility = View.VISIBLE
        actions.removeAllViews()
        pendingActions.clear()
        incomingBar.removeAllViews()
        incomingBar.visibility = if (ringing) View.VISIBLE else View.GONE
        actions.visibility = if (ringing) View.GONE else View.VISIBLE
        if (ringing) {
            instructionView.text = ""
            val decline = IosCallStyle.RoundAction(this, R.drawable.ic_ios_call_end, "Decline", if (compactScreen) 70 else 78).apply {
                setFill(IosCallStyle.RED)
                contentDescription = "Decline incoming call"
                setOnClickListener { if (!HopeCallController.reject(callId)) showCallActionFailure() }
            }
            val accept = IosCallStyle.RoundAction(this, R.drawable.ic_ios_call, "Accept", if (compactScreen) 70 else 78).apply {
                setFill(IosCallStyle.GREEN)
                contentDescription = "Answer incoming call"
                setOnClickListener { if (!HopeCallController.answer(callId)) showCallActionFailure() }
            }
            incomingBar.addView(decline, LinearLayout.LayoutParams(0, -2, 1f))
            incomingBar.addView(accept, LinearLayout.LayoutParams(0, -2, 1f))
            if (allowPrompt && !promptStarted &&
                (IncomingCallPreferences.isAnnouncementEnabled(this) ||
                    IncomingCallPreferences.isVoiceConfirmationEnabled(this))
            ) {
                promptStarted = true
                speakCallPrompt(call.caller)
            }
        } else if (activeOrHeld) {
            instructionView.text = ""
            addAction(
                "mute", R.drawable.ic_ios_mic_off, "Toggle microphone mute", active = call.muted
            ) { if (!HopeCallController.toggleMute()) showCallActionFailure() }
            addAction("keypad", R.drawable.ic_ios_keypad, "Open in-call keypad") { showKeypad() }
            addAction(
                "speaker", R.drawable.ic_ios_speaker, "Toggle speakerphone",
                active = call.speakerOn, enabled = call.canSpeaker
            ) { if (!call.canSpeaker || !HopeCallController.toggleSpeaker()) showCallActionFailure() }
            addAction(
                "add call", R.drawable.ic_ios_add, "Start another call"
            ) { if (!HopeCallController.addCall(this, callId)) showCallActionFailure() }
            addAction(
                if (holding) "resume" else "hold", R.drawable.ic_ios_pause,
                "Hold or resume call", active = holding, enabled = call.canHold
            ) { if (!call.canHold || !HopeCallController.toggleHold(callId)) showCallActionFailure() }
            if (call.canMerge) {
                addAction("merge", R.drawable.ic_ios_merge, "Merge supported calls into conference") {
                    if (!HopeCallController.mergeCalls(callId)) showCallActionFailure()
                }
            } else {
                addAction("contacts", R.drawable.ic_ios_contacts, "Open contacts") {
                    try {
                        startActivity(Intent(this, HopeDialerActivity::class.java)
                            .putExtra(HopeDialerActivity.EXTRA_START_PAGE, "contacts"))
                    } catch (error: Exception) {
                        showCallActionFailure()
                    }
                }
            }
        } else {
            // Outgoing (dialing / connecting / any other pre-connect state): same grid as iOS, with
            // the buttons that cannot work yet greyed out instead of hidden.
            instructionView.text = ""
            addAction("mute", R.drawable.ic_ios_mic_off, "Toggle microphone mute", active = call.muted) {
                if (!HopeCallController.toggleMute()) showCallActionFailure()
            }
            addAction(
                "keypad", R.drawable.ic_ios_keypad,
                "Keypad tones are available after the call connects", enabled = false
            ) { }
            addAction(
                "speaker", R.drawable.ic_ios_speaker, "Toggle speakerphone",
                active = call.speakerOn, enabled = call.canSpeaker
            ) { if (!call.canSpeaker || !HopeCallController.toggleSpeaker()) showCallActionFailure() }
            addAction("add call", R.drawable.ic_ios_add, "Add call is available after the call connects", enabled = false) { }
            addAction("hold", R.drawable.ic_ios_pause, "Hold is available after the call connects", enabled = false) { }
            addAction("contacts", R.drawable.ic_ios_contacts, "Open contacts") {
                try {
                    startActivity(Intent(this, HopeDialerActivity::class.java)
                        .putExtra(HopeDialerActivity.EXTRA_START_PAGE, "contacts"))
                } catch (error: Exception) {
                    showCallActionFailure()
                }
            }
        }
        flushActions()
        applyCallerBackground(call)
        applyScreenPolicy(call)
        applyOrbHeight(
            when {
                ringing -> 175
                activeOrHeld -> if (compactScreen) 290 else 335
                else -> if (compactScreen) 290 else 335
            }
        )
        updateOrbState()
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
        numberView.visibility = View.GONE
        screenTitleView.text = "Call ended"
        stateView.text = "Call Ended"
        stateView.visibility = View.VISIBLE
        standbyView.text = ""
        instructionView.text = ""
        timerView.visibility = View.GONE
        quickTextButton.visibility = View.GONE
        endCallButton.visibility = View.GONE
        incomingBar.visibility = View.GONE
        callActionDock.visibility = View.GONE
        actions.removeAllViews()
        actions.visibility = View.GONE
        updateOrbState("disconnected")
        if (!disconnectFinishScheduled) {
            disconnectFinishScheduled = true
            mainHandler.postDelayed(disconnectFinish, 1_500L)
        }
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
        iconRes: Int,
        description: String,
        active: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        pendingActions.add(IosCallStyle.RoundAction(this, iconRes, label, actionSizeDp).apply {
            contentDescription = description
            setActive(active)
            isEnabled = enabled
            setOnClickListener { onClick() }
        })
    }

    private fun showKeypad() {
        val typed = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(12))
            minHeight = dp(48)
        }
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")
        ).forEach { digits ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            digits.forEach { digit ->
                val key = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = IosCallStyle.rippleCircle(Color.argb(64, 255, 255, 255))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Send DTMF $digit"
                    setOnClickListener {
                        if (HopeCallController.sendDtmf(callId, digit[0])) {
                            typed.text = (typed.text.toString() + digit).takeLast(16)
                        } else {
                            Toast.makeText(
                                this@IncomingCallActivity,
                                "Android could not send that keypad tone.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                key.addView(TextView(this).apply {
                    text = digit
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    includeFontPadding = false
                })
                val letters = IosCallStyle.lettersFor(digit)
                if (letters.isNotEmpty()) {
                    key.addView(TextView(this).apply {
                        text = letters
                        textSize = 9f
                        letterSpacing = 0.15f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        includeFontPadding = false
                    })
                }
                row.addView(key, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                    setMargins(dp(8), dp(5), dp(8), dp(5))
                })
            }
            keys.addView(row, matchWidth())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(typed, matchWidth())
            addView(keys, matchWidth())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("Hide", null)
            .create()
        dialog.window?.setBackgroundDrawable(roundedBackground(Color.rgb(36, 38, 48), dp(28)))
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(IosCallStyle.BLUE)
    }

    private fun formatCallDuration(call: HopeCallSnapshot): String {
        if (call.connectTimeMillis <= 0L) return "00:00"
        val seconds = ((System.currentTimeMillis() - call.connectTimeMillis).coerceAtLeast(0L) / 1_000L)
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    /** Tells the HOPE ORB what the call is doing: ringing, connecting, active, on hold, ended. */
    private fun updateOrbState(forceState: String? = null) {
        if (::listeningView.isInitialized) {
            val snap = HopeCallController.currentCall(this, callId)
            val listening = forceState == null && speechStarted &&
                snap?.state == android.telecom.Call.STATE_RINGING
            listeningView.visibility = if (listening) View.VISIBLE else View.GONE
        }
        if (!::orbView.isInitialized) return
        val snapshot = HopeCallController.currentCall(this, callId)
        val state = forceState ?: when (snapshot?.state) {
            android.telecom.Call.STATE_RINGING -> if (speechStarted) "listening" else "ringing"
            android.telecom.Call.STATE_ACTIVE -> "active"
            android.telecom.Call.STATE_HOLDING -> "idle"
            android.telecom.Call.STATE_DISCONNECTED, null -> "disconnected"
            else -> "thinking"
        }
        orbView.setState(state)
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
        proximityLock.release()
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
