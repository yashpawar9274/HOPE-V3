package com.jarvis.assistant.ui.memory

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.memory.CoreBehaviorEngine
import com.jarvis.assistant.data.memory.CoreBehaviorHistory
import com.jarvis.assistant.data.memory.CoreBehaviorRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class CoreBehaviorActivity : AppCompatActivity() {
    private lateinit var entries: LinearLayout
    private lateinit var search: EditText
    private var rules: List<CoreBehaviorRule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(resolveColor(R.attr.themeBg, 0xFF10131A.toInt()))
        }
        content.addView(Button(this).apply {
            text = "BACK"
            setOnClickListener { finish() }
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "CORE BEHAVIOUR"
            textSize = 20f
            setTextColor(resolveColor(R.attr.themeTextPrimary, Color.WHITE))
            setPadding(0, dp(8), 0, dp(10))
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Explicit permanent rules are stored locally first and shared across HOPE personas. Conflicting rules are not silently replaced."
            textSize = 13f
            setTextColor(resolveColor(R.attr.themeTextSecondary, 0xFFBBBBBB.toInt()))
        }, matchWrap())
        search = EditText(this).apply {
            hint = "Search saved instructions"
            setSingleLine(true)
        }
        content.addView(search, matchWrap())
        content.addView(Button(this).apply {
            text = "ADD PERMANENT INSTRUCTION"
            setOnClickListener { showAddDialog() }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "VIEW INSTRUCTION HISTORY"
            setOnClickListener { showHistory() }
        }, matchWrap())
        val scroll = ScrollView(this)
        entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(entries)
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(content)

        search.addTextChangedListener(SimpleTextWatcher { render() })
        lifecycleScope.launch {
            JarvisApp.instance.coreBehaviorRepository.observeRules().collect { latest ->
                rules = latest
                render()
            }
        }
        lifecycleScope.launch {
            try {
                JarvisApp.instance.coreBehaviorEngine.syncFromCloud()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast("Core Behaviour cloud sync could not be completed: ${error.message}")
            }
        }
    }

    private fun render() {
        if (!::entries.isInitialized) return
        entries.removeAllViews()
        val query = search.text?.toString()?.trim().orEmpty()
        val visibleRules = rules.filter {
            query.isBlank() || it.instructionText.contains(query, true) ||
                it.category.contains(query, true) || it.scope.contains(query, true)
        }
        if (visibleRules.isEmpty()) {
            entries.addView(label(if (query.isBlank()) "No saved permanent instructions." else "No instructions match this search."))
        }
        visibleRules.forEach { rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(resolveColor(R.attr.themeInputBg, 0xFF20242D.toInt()))
            }
            card.addView(label(rule.instructionText, 15f))
            card.addView(label(
                "${rule.category.replace('_', ' ')} · ${rule.scope} · priority ${rule.priority} · " +
                    if (rule.activeStatus) "Active" else "Disabled",
                12f
            ))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(smallButton("EDIT") { showEditDialog(rule) }, weightWrap())
            actions.addView(smallButton(if (rule.activeStatus) "DISABLE" else "REACTIVATE") {
                updateRule(rule) {
                    JarvisApp.instance.coreBehaviorEngine.setActive(rule, !rule.activeStatus)
                }
            }, weightWrap())
            actions.addView(smallButton("DELETE") { confirmDelete(rule) }, weightWrap())
            card.addView(actions, matchWrap())
            val params = matchWrap()
            params.bottomMargin = dp(8)
            entries.addView(card, params)
        }
        lifecycleScope.launch {
            try {
                val recent = JarvisApp.instance.coreBehaviorEngine.history(100)
                    .filter { it.action == "applied" }
                    .take(6)
                if (recent.isNotEmpty()) {
                    entries.addView(label("RECENTLY APPLIED", 12f))
                    recent.forEach { entries.addView(label(historyLine(it), 12f)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@CoreBehaviorActivity, "Instruction history is unavailable: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddDialog() {
        val editor = EditText(this).apply {
            hint = "For example: Never promise a meeting before checking my calendar."
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add permanent instruction")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    try {
                        when (val result = JarvisApp.instance.coreBehaviorEngine.captureUserInstruction(editor.text.toString())) {
                            is CoreBehaviorEngine.CaptureResult.Saved -> toast(
                                when {
                                    result.syncError != null ->
                                        "Saved locally, but cloud sync could not be queued: ${result.syncError}"
                                    result.syncQueued -> "Saved locally; cloud sync queued."
                                    else -> "Saved locally. Cloud sync is off."
                                }
                            )
                            is CoreBehaviorEngine.CaptureResult.AlreadyExists -> toast("This instruction already exists.")
                            is CoreBehaviorEngine.CaptureResult.Conflict -> toast("No changes made; this conflicts with: ${result.existing.instructionText}")
                            CoreBehaviorEngine.CaptureResult.NeedsClarification -> toast("Please state the specific behavior to remember.")
                            null -> toast("Enter an explicit permanent rule to save it.")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        toast("The instruction was not confirmed as saved: ${error.message}")
                    }
                }
            }
            .show()
    }

    private fun showEditDialog(rule: CoreBehaviorRule) {
        val editor = EditText(this).apply {
            setText(rule.instructionText)
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit permanent instruction")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save changes") { _, _ ->
                updateRule(rule) {
                    JarvisApp.instance.coreBehaviorEngine.edit(rule, editor.text.toString())
                }
            }
            .show()
    }

    private fun confirmDelete(rule: CoreBehaviorRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete permanent instruction?")
            .setMessage("This will remove the active rule. Its local instruction history will remain.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        JarvisApp.instance.coreBehaviorEngine.delete(rule)
                        toast("Instruction deleted locally.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        toast(error.message ?: "Instruction could not be deleted.")
                    }
                }
            }
            .show()
    }

    private fun updateRule(rule: CoreBehaviorRule, action: suspend () -> CoreBehaviorRule) {
        lifecycleScope.launch {
            try {
                action()
                toast("Instruction updated.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast(error.message ?: "Instruction could not be updated.")
            }
        }
    }

    private fun showHistory() {
        lifecycleScope.launch {
            try {
                val history = JarvisApp.instance.coreBehaviorEngine.history(200)
                val body = history.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { historyLine(it) }
                    ?: "No instruction history yet."
                MaterialAlertDialogBuilder(this@CoreBehaviorActivity)
                    .setTitle("Instruction history")
                    .setMessage(body)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast("Instruction history could not be loaded: ${error.message}")
            }
        }
    }

    private fun historyLine(entry: CoreBehaviorHistory): String {
        val time = DateFormat.getDateTimeInstance().format(Date(entry.occurredAt))
        return "[$time] ${entry.action}${entry.surface?.let { " on $it" }.orEmpty()}: ${entry.instructionSnapshot}"
    }

    private fun label(textValue: String, size: Float = 14f) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(resolveColor(R.attr.themeTextPrimary, Color.WHITE))
        setPadding(dp(2), dp(5), dp(2), dp(5))
    }

    private fun smallButton(textValue: String, action: () -> Unit) =
        Button(this).apply {
            text = textValue
            textSize = 10f
            setOnClickListener { action() }
        }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightWrap() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

private class SimpleTextWatcher(private val onChanged: () -> Unit) :
    android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
