package com.jarvis.assistant.license

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The same HOPE ORB the main screen shows (assets/orb.html), set up exactly like HomeFragment does:
 * transparent hardware-layer WebView, file access on, loaded as orb.html?embedded=1.
 * States: idle, listening, thinking, speaking.
 */
internal class LicenseOrb(private val webView: WebView) {
    private var pageLoaded = false
    private var pendingState = "idle"

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        webView.apply {
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
                    pageLoaded = true
                    // These screens have a dark background, so use the orb's dark theme.
                    run("window.JarvisOrb && window.JarvisOrb.setTheme('dark');")
                    run("window.JarvisOrb && window.JarvisOrb.setState('$pendingState');")
                }
            }
            loadUrl("file:///android_asset/orb.html?embedded=1")
        }
    }

    fun setState(state: String) {
        pendingState = state
        if (pageLoaded) run("window.JarvisOrb && window.JarvisOrb.setState('$state');")
    }

    fun resume() = webView.onResume()

    fun pause() = webView.onPause()

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    private fun run(script: String) {
        webView.evaluateJavascript(script, null)
    }
}
