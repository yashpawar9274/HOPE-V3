package com.jarvis.assistant.ui.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.clipboard.ClipboardAnalysisManager
import com.jarvis.assistant.clipboard.ClipboardContent
import com.jarvis.assistant.clipboard.ClipboardContentType
import com.jarvis.assistant.clipboard.ClipboardOrbState
import com.jarvis.assistant.clipboard.ClipboardReadResult
import com.jarvis.assistant.clipboard.SmartClipboardRequest
import com.jarvis.assistant.clipboard.SmartClipboardViewModel
import com.jarvis.assistant.util.HopeSpeech
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmartClipboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONTENT_TYPE = "smart_clipboard.content_type"
        const val EXTRA_FINGERPRINT = "smart_clipboard.fingerprint"
        const val EXTRA_TEXT = "smart_clipboard.text"
        const val EXTRA_IMAGE_URI = "smart_clipboard.image_uri"
        const val EXTRA_MIME_TYPE = "smart_clipboard.mime_type"
        const val EXTRA_CATEGORY = "smart_clipboard.category"
        const val EXTRA_IS_SENSITIVE = "smart_clipboard.is_sensitive"
        const val EXTRA_IS_TOO_LARGE = "smart_clipboard.is_too_large"
        private const val PREVIEW_MAX_CHARS = 4_000
        const val EXTRA_READ_ON_RESUME = "smart_clipboard.read_on_resume"
        const val EXTRA_REQUEST = "smart_clipboard.request"
    }

    private val app by lazy { application as JarvisApp }
    private val preferences by lazy { app.preferences }
    private lateinit var model: SmartClipboardViewModel
    private lateinit var clipboardManager: ClipboardAnalysisManager
    private lateinit var orbWebView: WebView
    private lateinit var statusText: TextView
    private lateinit var categoryText: TextView
    private lateinit var previewText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var responseText: TextView
    private lateinit var errorText: TextView
    private lateinit var analyzeTextButton: Button
    private lateinit var analyzeImageButton: Button
    private lateinit var explainButton: Button
    private lateinit var copyButton: Button
    private lateinit var speakButton: Button
    private var previewJob: kotlinx.coroutines.Job? = null
    private var pendingInitialContent: ClipboardContent? = null
    private var pendingClipboardRead = false
    private var pendingVoiceRequest: SmartClipboardRequest? = null
    private var speakNextResponse = false
    private var lastSpokenResponse: String? = null
    private var orbPageLoaded = false
    private var pendingOrbState = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        clipboardManager = app.clipboardAnalysisManager
        model = ViewModelProvider(this, viewModelFactory())[SmartClipboardViewModel::class.java]
        pendingInitialContent = contentFromIntent(intent)
        pendingClipboardRead = intent.getBooleanExtra(EXTRA_READ_ON_RESUME, false)
        pendingVoiceRequest = intent.getStringExtra(EXTRA_REQUEST)
            ?.let { runCatching { SmartClipboardRequest.valueOf(it) }.getOrNull() }
        setIntent(Intent(this, SmartClipboardActivity::class.java))
        setContentView(createScreen())
        setupActions()
        observeState()
    }

    override fun onPostResume() {
        super.onPostResume()
        pendingInitialContent?.let {
            model.load(it, analyzeTextAutomatically = false)
            pendingInitialContent = null
        }
        if (pendingClipboardRead) {
            pendingClipboardRead = false
            readClipboardForVoiceRequest()
        }
    }

    override fun onPause() {
        if (::orbWebView.isInitialized) {
            orbWebView.onPause()
            orbWebView.pauseTimers()
        }
        model.cancelProcessing()
        HopeSpeech.stop("smart_clipboard_activity_response")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::orbWebView.isInitialized) {
            orbWebView.onResume()
            orbWebView.resumeTimers()
            syncOrbTheme()
            setOrbState(pendingOrbState)
        }
    }

    override fun onStop() {
        previewJob?.cancel()
        previewImage.setImageDrawable(null)
        model.clear()
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingClipboardRead = intent.getBooleanExtra(EXTRA_READ_ON_RESUME, false)
        pendingVoiceRequest = intent.getStringExtra(EXTRA_REQUEST)
            ?.let { runCatching { SmartClipboardRequest.valueOf(it) }.getOrNull() }
        val content = contentFromIntent(intent)
        setIntent(Intent(this, SmartClipboardActivity::class.java))
        if (content != null) {
            model.load(content, analyzeTextAutomatically = false)
        }
        if (pendingClipboardRead && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            readClipboardForVoiceRequest()
        }
    }

    override fun onDestroy() {
        HopeSpeech.stop("smart_clipboard_activity_response")
        if (::orbWebView.isInitialized) {
            orbPageLoaded = false
            orbWebView.stopLoading()
            orbWebView.loadUrl("about:blank")
            (orbWebView.parent as? android.view.ViewGroup)?.removeView(orbWebView)
            orbWebView.destroy()
        }
        super.onDestroy()
    }

    private fun viewModelFactory() = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SmartClipboardViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SmartClipboardViewModel(
                application as Application,
                app.clipboardAIRepository,
                preferences
            ) as T
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOriginalOrbWebView() {
        orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.startsWith("file:///android_asset/orb.html") != true) return
                    orbPageLoaded = true
                    syncOrbTheme()
                    setOrbState(pendingOrbState)
                }
            }
            loadUrl("file:///android_asset/orb.html?embedded=1")
        }
    }

    private fun setOrbState(state: String) {
        pendingOrbState = when (state) {
            "listening", "thinking", "speaking" -> state
            else -> "idle"
        }
        if (orbPageLoaded) {
            orbWebView.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setState('$pendingOrbState');",
                null
            )
        }
    }

    private fun syncOrbTheme() {
        if (!orbPageLoaded) return
        val theme = preferences.appTheme
        val customHex = preferences.customThemeColor
        val script = if (theme == "custom" && customHex.matches(Regex("""#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?"""))) {
            "window.JarvisOrb && window.JarvisOrb.setBackground('$customHex');"
        } else {
            val mapped = when (theme) {
                "dark" -> "dark"
                "red" -> "red"
                "sunset" -> "sunset"
                else -> "light"
            }
            "window.JarvisOrb && window.JarvisOrb.setTheme('$mapped');"
        }
        orbWebView.evaluateJavascript(script, null)
    }

    private fun createScreen(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(24))
            ThemeManager.applyDynamicBackground(this)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }

        page.addView(label("SMART CLIPBOARD", 24f, bold = true, center = true))
        page.addView(label(
            "Understand copied text and images with HOPE",
            14f,
            center = true,
            marginTop = 4
        ))

        val orbFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            ).apply {
                topMargin = dp(8)
                marginStart = -dp(20)
                marginEnd = -dp(20)
            }
            clipChildren = false
            clipToPadding = false
        }
        orbWebView = WebView(this).apply {
            contentDescription = "Original HOPE animated orb"
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        setupOriginalOrbWebView()
        orbFrame.addView(orbWebView)
        page.addView(orbFrame)
        statusText = label("Clipboard analysis is off", 13f, center = true)
        page.addView(statusText)

        page.addView(label(
            "Smart Clipboard controls are in Settings → AI Features. Clipboard content is read only after an explicit request; HOPE never monitors clipboard changes.",
            12f
        ))

        page.addView(sectionTitle("Detected content"))
        val previewCard = card()
        val previewColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        categoryText = label("No content detected", 12f, bold = true)
        previewText = label(
            "Ask HOPE what you copied, or tap Check Clipboard.",
            15f
        )
        previewImage = ImageView(this).apply {
            visibility = View.GONE
            contentDescription = "Preview of the copied image"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply { topMargin = dp(10) }
        }
        previewColumn.addView(categoryText)
        previewColumn.addView(previewText)
        previewColumn.addView(previewImage)
        previewCard.addView(previewColumn)
        page.addView(previewCard)

        val checkButton = actionButton("Check Clipboard")
        page.addView(checkButton)
        analyzeTextButton = actionButton("Analyze Text")
        analyzeImageButton = actionButton("Analyze Image")
        page.addView(analyzeTextButton)
        page.addView(analyzeImageButton)

        page.addView(sectionTitle("HOPE's response"))
        val responseCard = card()
        val responseColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        responseText = label("The response will appear here.", 15f)
        responseColumn.addView(responseText)
        responseCard.addView(responseColumn)
        page.addView(responseCard)
        errorText = label("", 13f).apply {
            setTextColor(Color.rgb(190, 40, 55))
            visibility = View.GONE
        }
        page.addView(errorText)

        val responseActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        copyButton = actionButton("Copy Response")
        speakButton = actionButton("Speak Response")
        explainButton = actionButton("Explain More")
        responseActions.addView(copyButton, weightedButtonParams())
        responseActions.addView(speakButton, weightedButtonParams())
        page.addView(responseActions)
        page.addView(explainButton)
        page.addView(actionButton("Dismiss").apply {
            setOnClickListener {
                model.clear()
                finish()
            }
        })

        checkButton.setOnClickListener {
            if (!preferences.isSmartClipboardEnabled) {
                Toast.makeText(this, "Enable Smart Clipboard to read copied content.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when (val result = clipboardManager.readCurrentClip(this)) {
                ClipboardReadResult.Empty ->
                    Toast.makeText(this, "No readable text or supported image is on the clipboard.", Toast.LENGTH_SHORT).show()
                ClipboardReadResult.BackgroundRestricted ->
                    Toast.makeText(this, "Android requires HOPE to be visible before it can read the clipboard.", Toast.LENGTH_LONG).show()
                is ClipboardReadResult.Unavailable ->
                    Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                is ClipboardReadResult.Content ->
                    model.load(result.value, analyzeTextAutomatically = false)
            }
        }
        return scroll
    }

    private fun setupActions() {
        analyzeTextButton.setOnClickListener { model.analyzeText() }
        analyzeImageButton.setOnClickListener { confirmImageAnalysis(explainMore = false) }
        explainButton.setOnClickListener {
            val content = model.state.value.content
            if (content?.type == ClipboardContentType.IMAGE) confirmImageAnalysis(explainMore = true)
            else model.analyzeText(explainMore = true)
        }
        copyButton.setOnClickListener {
            val response = model.state.value.response ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HOPE response", response))
            Toast.makeText(this, "Response copied.", Toast.LENGTH_SHORT).show()
        }
        speakButton.setOnClickListener {
            val response = model.state.value.response ?: return@setOnClickListener
            speakResponse(response)
        }
    }

    private fun readClipboardForVoiceRequest() {
        pendingClipboardRead = false
        model.clear()
        speakNextResponse = false
        lastSpokenResponse = null
        if (!preferences.isSmartClipboardEnabled) {
            speakClipboardError("Boss, Smart Clipboard paused hai. Settings mein enable karke dobara try karo.")
            return
        }
        when (val result = clipboardManager.readCurrentClip(this)) {
            ClipboardReadResult.Empty ->
                speakClipboardError("Boss, clipboard khaali hai ya supported text/image nahi mila.")
            ClipboardReadResult.BackgroundRestricted ->
                speakClipboardError("Boss, Android ne clipboard access allow nahi kiya. HOPE ko foreground mein rakhkar dobara try karo.")
            is ClipboardReadResult.Unavailable ->
                speakClipboardError("Boss, clipboard read nahi ho paaya. ${result.reason.take(160)}")
            is ClipboardReadResult.Content -> {
                val request = pendingVoiceRequest ?: SmartClipboardRequest.EXPLAIN
                speakNextResponse = preferences.isSmartClipboardVoiceResponseEnabled
                model.load(result.value, analyzeTextAutomatically = false)
                if (request == SmartClipboardRequest.EXPLAIN_IMAGE &&
                    result.value.type != ClipboardContentType.IMAGE
                ) {
                    speakClipboardError("Boss, clipboard par copied image nahi mili.")
                } else when (result.value.type) {
                    ClipboardContentType.TEXT -> {
                        if (!preferences.isSmartClipboardTextAnalysisEnabled) {
                            speakClipboardError("Boss, text analysis settings mein disabled hai.")
                        } else {
                            model.analyzeText(request = request)
                        }
                    }
                    ClipboardContentType.IMAGE -> {
                        if (!preferences.isSmartClipboardImageAnalysisEnabled) {
                            speakClipboardError("Boss, image analysis settings mein disabled hai.")
                        } else {
                            confirmImageAnalysis(explainMore = false, request = request)
                        }
                    }
                }
            }
        }
        pendingVoiceRequest = null
    }

    private fun speakClipboardError(message: String) {
        statusText.text = message
        if (preferences.isSmartClipboardVoiceResponseEnabled) {
            speakResponse(message)
        }
    }

    private fun speakResponse(text: String) {
        val started = HopeSpeech.speak(
            applicationContext,
            text,
            preferences.language,
            "smart_clipboard_activity_response",
            queueMode = android.speech.tts.TextToSpeech.QUEUE_FLUSH
        ) { completed ->
            runOnUiThread {
                statusText.text = if (completed) "Response ready" else "Speech could not be played"
                setOrbState("idle")
            }
        }
        if (started) {
            statusText.text = "Speaking response"
            setOrbState("speaking")
        } else {
            Toast.makeText(this, "Speech could not be started.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmImageAnalysis(
        explainMore: Boolean,
        request: SmartClipboardRequest = SmartClipboardRequest.EXPLAIN_IMAGE
    ) {
        if (!preferences.isSmartClipboardEnabled ||
            !preferences.isSmartClipboardImageAnalysisEnabled
        ) {
            Toast.makeText(
                this,
                "Enable Smart Clipboard and image analysis before sending an image.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Analyze copied image?")
            .setMessage(
                "The image will be sent to your configured Gemini service. Continue only if it does not contain passwords, verification codes, payment details, or other sensitive information."
            )
            .setNegativeButton("Cancel") { _, _ ->
                speakNextResponse = false
            }
            .setPositiveButton("Analyze") { _, _ ->
                model.analyzeImage(explainMore, request)
            }
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    render(state)
                    if (speakNextResponse && !state.isProcessing) {
                        val response = state.response
                        val error = state.error
                        if (response != null && response != lastSpokenResponse) {
                            lastSpokenResponse = response
                            speakNextResponse = false
                            speakResponse("Boss, $response")
                        } else if (error != null) {
                            speakNextResponse = false
                            speakResponse("Boss, $error")
                        }
                    }
                }
            }
        }
    }

    private fun render(state: com.jarvis.assistant.clipboard.ClipboardUiState) {
        val content = state.content
        if (content == null) {
            categoryText.text = "No content detected"
            previewText.text = "Ask HOPE what you copied, or tap Check Clipboard."
            previewImage.visibility = View.GONE
            statusText.text = if (preferences.isSmartClipboardEnabled) {
                "Ready for an explicit clipboard request"
            } else {
                "Clipboard analysis is off"
            }
            responseText.text = "The response will appear here."
            analyzeTextButton.visibility = View.GONE
            analyzeImageButton.visibility = View.GONE
            explainButton.visibility = View.GONE
            copyButton.visibility = View.GONE
            speakButton.visibility = View.GONE
            errorText.visibility = View.GONE
            setOrbState("idle")
            return
        }

        if (content.isSensitive) {
            categoryText.text = "Hidden for privacy"
            previewText.text = "Sensitive-looking content was detected. It was not sent to Gemini."
            previewImage.visibility = View.GONE
        } else if (content.type == ClipboardContentType.TEXT) {
            categoryText.text = if (content.isTooLarge) {
                "Text • preview shortened"
            } else {
                content.category?.displayName ?: "Text"
            }
            previewText.text = content.text.orEmpty().take(PREVIEW_MAX_CHARS)
            previewImage.visibility = View.GONE
        } else {
            categoryText.text = "Image • ${content.mimeType ?: "type unknown"}"
            previewText.text = "Copied image • limited to 8 MB • request analysis explicitly to send it to Gemini"
            previewImage.visibility = View.VISIBLE
            loadImagePreview(content)
        }

        statusText.text = when {
            state.orbState == ClipboardOrbState.CLIPBOARD_READING -> "Reading copied content"
            state.orbState == ClipboardOrbState.ANALYZING || state.isProcessing ->
                "Thinking about your copied content"
            state.orbState == ClipboardOrbState.RESPONDING -> "Speaking response"
            state.error != null -> "Analysis needs attention"
            state.response != null -> "Response ready"
            else -> "Content read on this device"
        }
        responseText.text = state.response ?: if (state.isProcessing) {
            "HOPE is analyzing the content..."
        } else {
            "The response will appear here."
        }
        errorText.text = state.error.orEmpty()
        errorText.visibility = if (state.error == null) View.GONE else View.VISIBLE
        analyzeTextButton.visibility =
            if (content.type == ClipboardContentType.TEXT && !content.isSensitive && !content.isTooLarge && state.response == null && !state.isProcessing) View.VISIBLE else View.GONE
        analyzeImageButton.visibility =
            if (content.type == ClipboardContentType.IMAGE && !state.isProcessing) View.VISIBLE else View.GONE
        explainButton.visibility = if (state.response != null && !state.isProcessing) View.VISIBLE else View.GONE
        copyButton.visibility = if (state.response != null) View.VISIBLE else View.GONE
        speakButton.visibility = if (state.response != null) View.VISIBLE else View.GONE
        setOrbState(
            when (state.orbState) {
                ClipboardOrbState.LISTENING -> "listening"
                ClipboardOrbState.CLIPBOARD_READING,
                ClipboardOrbState.ANALYZING -> "thinking"
                ClipboardOrbState.RESPONDING -> "speaking"
                ClipboardOrbState.ERROR,
                ClipboardOrbState.IDLE -> "idle"
            }
        )
    }

    private fun loadImagePreview(content: ClipboardContent) {
        previewJob?.cancel()
        val uri = content.imageUri ?: return
        previewJob = lifecycleScope.launch {
            try {
                val bitmap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    app.clipboardImageProcessor.loadPreview(uri, content.mimeType)
                }
                if (model.state.value.content?.fingerprint == content.fingerprint) {
                    previewImage.setImageBitmap(bitmap)
                } else {
                    bitmap.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (model.state.value.content?.fingerprint == content.fingerprint) {
                    previewImage.setImageDrawable(null)
                    previewText.text =
                        "The copied image preview is unavailable. You can still try Analyze Image."
                }
            }
        }
    }

    private fun contentFromIntent(source: android.content.Intent): ClipboardContent? {
        val type = source.getStringExtra(EXTRA_CONTENT_TYPE)
            ?.let { runCatching { ClipboardContentType.valueOf(it) }.getOrNull() }
            ?: return null
        val fingerprint = source.getStringExtra(EXTRA_FINGERPRINT) ?: return null
        val category = source.getStringExtra(EXTRA_CATEGORY)
            ?.let { runCatching { com.jarvis.assistant.clipboard.ClipboardTextCategory.valueOf(it) }.getOrNull() }
        return ClipboardContent(
            type = type,
            fingerprint = fingerprint,
            text = source.getStringExtra(EXTRA_TEXT),
            imageUri = source.getStringExtra(EXTRA_IMAGE_URI)?.let(android.net.Uri::parse),
            mimeType = source.getStringExtra(EXTRA_MIME_TYPE),
            category = category,
            isSensitive = source.getBooleanExtra(EXTRA_IS_SENSITIVE, false),
            isTooLarge = source.getBooleanExtra(EXTRA_IS_TOO_LARGE, false)
        )
    }

    private fun label(
        text: String,
        size: Float,
        bold: Boolean = false,
        center: Boolean = false,
        marginTop: Int = 0
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(resolveThemeColor(R.attr.themeTextPrimary, Color.DKGRAY))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (center) gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(marginTop) }
    }

    private fun sectionTitle(text: String) = label(text, 16f, bold = true).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(resolveThemeColor(R.attr.themeBg, Color.WHITE))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    private fun actionButton(text: String): Button = com.google.android.material.button.MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        contentDescription = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun weightedButtonParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                runCatching { getColor(value.resourceId) }.getOrDefault(value.data)
            } else {
                value.data
            }
        } else {
            fallback
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
