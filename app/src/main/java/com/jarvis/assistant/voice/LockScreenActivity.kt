package com.jarvis.assistant.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.HopeAssistantState
import com.jarvis.assistant.databinding.ActivityLockScreenBinding
import com.jarvis.assistant.license.LicenseManager
import com.jarvis.assistant.security.AppIdentityVerifier
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.launch
import java.util.Date

class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private var orbPageLoaded = false
    private var orbPageFailed = false
    private var pendingOrbState = "idle"
    private var receiversRegistered = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockUpdater = object : Runnable {
        override fun run() {
            updateClock()
            val now = System.currentTimeMillis()
            clockHandler.postDelayed(this, 60_000L - now % 60_000L)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pauseOrbRendering()
                Intent.ACTION_SCREEN_ON -> {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        resumeOrbRendering()
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (!LicenseManager.guardAllows(this) || !AppIdentityVerifier.isTrustedRelease(this)) {
            finish()
            return
        }

        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.rgb(7, 9, 20)
        window.navigationBarColor = Color.rgb(7, 9, 20)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        updateClock()
        setupOrb()
        observeConversation()
        handleAssistantVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantVoiceIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val stickyBatteryIntent = ContextCompat.registerReceiver(
            this,
            systemReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiversRegistered = true
        stickyBatteryIntent?.let(::updateBattery)
    }

    override fun onResume() {
        super.onResume()
        clockHandler.removeCallbacks(clockUpdater)
        clockHandler.post(clockUpdater)
        resumeOrbRendering()
        renderConversationState()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockUpdater)
        pauseOrbRendering()
        super.onPause()
    }

    override fun onStop() {
        if (receiversRegistered) {
            unregisterReceiver(systemReceiver)
            receiversRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockUpdater)
        if (::binding.isInitialized) {
            binding.lockOrbWebView.apply {
                stopLoading()
                webViewClient = WebViewClient()
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
        }
        super.onDestroy()
    }

    private fun setupOrb() {
        binding.lockOrbWebView.settings.javaScriptEnabled = true
        binding.lockOrbWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                orbPageLoaded = true
                view.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('dark');", null)
                setOrbState(pendingOrbState)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    orbPageFailed = true
                    renderError("HOPE DISPLAY UNAVAILABLE")
                }
            }
        }
        binding.lockOrbWebView.loadUrl("file:///android_asset/orb.html?embedded=1")
    }

    private fun observeConversation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    JarvisConversationService.conversationState.collect {
                        renderConversationState()
                    }
                }
                launch {
                    JarvisConversationService.voiceAssistantState.collect {
                        renderConversationState()
                    }
                }
                launch {
                    JarvisConversationService.isStandby.collect {
                        renderConversationState()
                    }
                }
                launch {
                    JarvisConversationService.isSessionOn.collect {
                        renderConversationState()
                    }
                }
                launch {
                    JarvisConversationService.liveError.collect {
                        renderConversationState()
                    }
                }
                launch {
                    JarvisConversationService.audioFrequency.collect { frequency ->
                        if (orbPageLoaded && !orbPageFailed) {
                            binding.lockOrbWebView.evaluateJavascript(
                                "window.JarvisOrb && window.JarvisOrb.setExternalAudio(" +
                                    "${frequency.amplitude},${frequency.bass},${frequency.high});",
                                null
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderConversationState() {
        if (!::binding.isInitialized || orbPageFailed) return

        val assistantState = JarvisConversationService.voiceAssistantState.value
        val liveError = JarvisConversationService.liveError.value
        if (assistantState == HopeAssistantState.ERROR || liveError != null) {
            renderError("HOPE NEEDS ATTENTION")
            return
        }

        val (orbState, label) = when {
            JarvisConversationService.isStandby.value ||
                assistantState == HopeAssistantState.STANDBY ->
                "idle" to "STANDBY • SAY HEY HOPE"
            assistantState == HopeAssistantState.WAKE_WORD_DETECTED ->
                "listening" to "LISTENING"
            !JarvisConversationService.isSessionOn.value &&
                assistantState == HopeAssistantState.OFF ->
                "idle" to "ACTIVATE HOPE WITH YOUR ASSISTANT"
            else -> when (JarvisConversationService.conversationState.value) {
                ConversationState.LISTENING -> "listening" to "LISTENING"
                ConversationState.THINKING -> "thinking" to "THINKING"
                ConversationState.SPEAKING -> "speaking" to "SPEAKING"
                ConversationState.IDLE -> "idle" to "HOPE READY"
            }
        }
        setOrbState(orbState)
        binding.tvLockStatus.text = label
        binding.tvLockStatus.setTextColor(Color.rgb(148, 163, 184))
    }

    private fun renderError(message: String) {
        if (!::binding.isInitialized) return
        setOrbState("idle")
        binding.tvLockStatus.text = message
        binding.tvLockStatus.setTextColor(Color.rgb(248, 113, 113))
    }

    private fun setOrbState(state: String) {
        pendingOrbState = state
        if (!orbPageLoaded || orbPageFailed || !::binding.isInitialized) return
        binding.lockOrbWebView.evaluateJavascript(
            "window.JarvisOrb && window.JarvisOrb.setState('$state');",
            null
        )
    }

    private fun updateClock() {
        if (!::binding.isInitialized) return
        binding.tvLockClock.text = android.text.format.DateFormat
            .getTimeFormat(this)
            .format(Date())
        binding.tvLockDate.text = android.text.format.DateFormat
            .getLongDateFormat(this)
            .format(Date())
    }

    private fun updateBattery(intent: Intent) {
        if (!::binding.isInitialized) return
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", -1)
        binding.tvLockBattery.text = if (level >= 0 && scale > 0) {
            "BATTERY  •  ${level * 100 / scale}%"
        } else {
            "BATTERY  •  --"
        }
    }

    private fun pauseOrbRendering() {
        if (!::binding.isInitialized) return
        binding.lockOrbWebView.onPause()
        binding.lockOrbWebView.pauseTimers()
    }

    private fun resumeOrbRendering() {
        if (!::binding.isInitialized) return
        binding.lockOrbWebView.onResume()
        binding.lockOrbWebView.resumeTimers()
    }

    private fun handleAssistantVoiceIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(
                HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION,
                false
            ) != true
        ) return
        intent.removeExtra(HopeVoiceInteractionSessionService.EXTRA_START_ASSISTANT_VOICE_SESSION)

        if (JarvisConversationService.isSessionOn.value) return
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            renderError("OPEN HOPE TO ENABLE VOICE ACCESS")
            return
        }
        if (JarvisApp.instance.preferences.apiKey.isBlank()) {
            renderError("OPEN HOPE TO CONFIGURE VOICE ACCESS")
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, JarvisConversationService::class.java).apply {
                    action = JarvisConversationService.ACTION_START_CONVERSATION
                }
            )
        } catch (error: Exception) {
            android.util.Log.e("LockScreenActivity", "Could not start HOPE voice service.", error)
            renderError("ANDROID BLOCKED VOICE START")
        }
    }
}
