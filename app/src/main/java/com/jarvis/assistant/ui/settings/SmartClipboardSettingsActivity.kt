package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.clipboard.SmartClipboardActivity
import com.jarvis.assistant.util.ThemeManager
import java.text.DateFormat

class SmartClipboardSettingsActivity : AppCompatActivity() {
    private val preferences by lazy { (application as JarvisApp).preferences }
    private lateinit var historyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(createContent())
    }

    private fun createContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            ThemeManager.applyDynamicBackground(this)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        content.addView(text("AI FEATURES", 12f, bold = true, muted = true))
        content.addView(text("Smart Clipboard Intelligence", 24f, bold = true, top = 4))
        content.addView(
            text(
                "Clipboard contents are never monitored. HOPE reads them only after an explicit voice request or a deliberate Check Clipboard action.",
                14f,
                top = 8
            )
        )

        content.addView(toggle("Enable Smart Clipboard", preferences.isSmartClipboardEnabled) {
            preferences.isSmartClipboardEnabled = it
        })
        content.addView(toggle("Text Analysis", preferences.isSmartClipboardTextAnalysisEnabled) {
            preferences.isSmartClipboardTextAnalysisEnabled = it
        })
        content.addView(toggle("Image Analysis", preferences.isSmartClipboardImageAnalysisEnabled) {
            preferences.isSmartClipboardImageAnalysisEnabled = it
        })
        content.addView(toggle("Voice Response", preferences.isSmartClipboardVoiceResponseEnabled) {
            preferences.isSmartClipboardVoiceResponseEnabled = it
        })
        content.addView(
            text(
                "Sensitive-looking text is blocked locally using best-effort checks. Images are sent only after an explicit confirmation. Android 10+ can restrict clipboard reads while HOPE is in the background.",
                12f,
                top = 4
            )
        )

        content.addView(toggle("Clipboard History (off by default)", preferences.isSmartClipboardHistoryEnabled) {
            preferences.isSmartClipboardHistoryEnabled = it
            historyButton.visibility = if (it) View.VISIBLE else View.GONE
        })
        historyButton = button("View saved history").apply {
            visibility = if (preferences.isSmartClipboardHistoryEnabled) View.VISIBLE else View.GONE
            setOnClickListener { showHistory() }
        }
        content.addView(historyButton)
        content.addView(button("Clear History").apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SmartClipboardSettingsActivity)
                    .setTitle("Clear clipboard history?")
                    .setMessage("This permanently removes the locally encrypted clipboard analysis history.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        preferences.clearSmartClipboardHistory()
                        Toast.makeText(
                            this@SmartClipboardSettingsActivity,
                            "Clipboard history cleared.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
        })
        content.addView(button("Privacy Information").apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SmartClipboardSettingsActivity)
                    .setTitle("Smart Clipboard privacy")
                    .setMessage(
                        "HOPE does not monitor clipboard changes or store a history unless you enable it. Copied content is sent to your configured Gemini service only after an explicit request and the corresponding analysis setting is enabled. Sensitive-text checks are best-effort; do not analyze credentials or payment details. Android background clipboard restrictions are respected."
                    )
                    .setPositiveButton("Close", null)
                    .show()
            }
        })
        content.addView(button("Open Smart Clipboard").apply {
            setOnClickListener {
                startActivity(
                    android.content.Intent(
                        this@SmartClipboardSettingsActivity,
                        SmartClipboardActivity::class.java
                    )
                )
            }
        })
        return scroll
    }

    private fun toggle(
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) = SwitchMaterial(this).apply {
        text = title
        isChecked = checked
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
    }

    private fun button(title: String) = Button(this).apply {
        text = title
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun text(
        value: String,
        size: Float,
        bold: Boolean = false,
        muted: Boolean = false,
        top: Int = 0
    ) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        if (muted) setTextColor(Color.GRAY)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun showHistory() {
        val history = preferences.getSmartClipboardHistory()
        if (history == null) {
            Toast.makeText(this, "Encrypted history is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        if (history.isEmpty()) {
            Toast.makeText(this, "No saved clipboard analyses.", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = history.map { entry ->
            "${DateFormat.getDateTimeInstance().format(java.util.Date(entry.timestamp))} • ${entry.type}\n" +
                "${entry.copiedContent.take(180)}\n\n${entry.response.take(260)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved clipboard history")
            .setItems(entries, null)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
