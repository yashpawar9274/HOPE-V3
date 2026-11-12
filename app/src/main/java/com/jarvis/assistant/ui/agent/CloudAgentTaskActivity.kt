package com.jarvis.assistant.ui.agent

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.agent.AndroidCloudToolBridge
import com.jarvis.assistant.data.agent.CloudAgentException
import com.jarvis.assistant.data.agent.CloudAgentTaskUpdate
import com.jarvis.assistant.data.agent.CloudAgentToolPolicy
import com.jarvis.assistant.data.agent.CloudAgentToolRequest
import com.jarvis.assistant.data.agent.ToolResultStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CloudAgentTaskActivity : AppCompatActivity() {
    private val repository by lazy { JarvisApp.instance.cloudAgentRepository }
    private val bridge by lazy { AndroidCloudToolBridge(this) }
    private lateinit var objectiveInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var taskContent: LinearLayout
    private lateinit var submitButton: Button
    private lateinit var refreshButton: Button
    private lateinit var cancelTaskButton: Button
    private var taskId: String? = null
    private val processingCalls = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (!repository.isEnabledForCurrentUser()) {
            Toast.makeText(this, "Enable Cloud Agent consent in Memory settings first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        taskId = savedInstanceState?.getString(STATE_TASK_ID)
        buildScreen()
        taskId?.let { loadTask(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TASK_ID, taskId)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(resolveColor(R.attr.themeBg, 0xFF10131A.toInt()))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(80), -2))
        header.addView(label("Cloud Agent", 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val inputLayout = TextInputLayout(this).apply {
            hint = "What should HOPE help with?"
            setPadding(0, dp(12), 0, dp(4))
        }
        objectiveInput = TextInputEditText(inputLayout.context).apply {
            minLines = 2
            maxLines = 4
            setTextColor(resolveColor(R.attr.themeTextPrimary, Color.WHITE))
        }
        inputLayout.addView(objectiveInput)
        root.addView(inputLayout)
        submitButton = Button(this).apply {
            text = "Create cloud task"
            setOnClickListener { createTask() }
        }
        root.addView(submitButton, matchWrap())

        statusText = label("Tasks are only planned in the cloud. Every device request requires your confirmation here.", 14f)
        root.addView(statusText, matchWrap())
        refreshButton = Button(this).apply {
            text = "Refresh task"
            isEnabled = taskId != null
            setOnClickListener { taskId?.let(::loadTask) }
        }
        root.addView(refreshButton, matchWrap())
        cancelTaskButton = Button(this).apply {
            text = "Cancel task"
            isEnabled = false
            setOnClickListener {
                MaterialAlertDialogBuilder(this@CloudAgentTaskActivity)
                    .setTitle("Cancel this task?")
                    .setMessage("Pending device requests will be revoked. No further action will be run.")
                    .setNegativeButton("Keep task", null)
                    .setPositiveButton("Cancel task") { _, _ -> cancelTask() }
                    .show()
            }
        }
        root.addView(cancelTaskButton, matchWrap())
        val scroll = ScrollView(this)
        taskContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        scroll.addView(taskContent)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun createTask() {
        val objective = objectiveInput.text?.toString()?.trim().orEmpty()
        if (objective.isBlank()) {
            objectiveInput.error = "Enter a task for HOPE."
            return
        }
        submitButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val task = repository.createTask(objective)
                taskId = task.taskId
                refreshButton.isEnabled = true
                statusText.text = "Cloud task created. Waiting for the Cloud Run worker to plan it."
                loadTask(task.taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            } finally {
                submitButton.isEnabled = true
            }
        }
    }

    private fun loadTask(id: String) {
        refreshButton.isEnabled = false
        lifecycleScope.launch {
            try {
                var update = repository.getTask(id)
                var taskCannotFinish = false
                if (update.task.state in setOf("awaiting_approval", "waiting_for_device") &&
                    update.toolRequests.isEmpty()
                ) {
                    try {
                        repository.finishTask(id)
                        update = repository.getTask(id)
                    } catch (error: CloudAgentException) {
                        if (error.httpCode != 409) throw error
                        taskCannotFinish = true
                    }
                }
                render(update)
                if (taskCannotFinish) {
                    statusText.text = "No live device requests remain. The task may have expired; cancel it if it cannot continue."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            } finally {
                refreshButton.isEnabled = true
            }
        }
    }

    private fun render(update: CloudAgentTaskUpdate) {
        statusText.text = "Task ${update.task.state.replace('_', ' ')} · ${update.task.taskId}"
        cancelTaskButton.isEnabled = update.task.state !in setOf("completed", "failed", "cancelled")
        taskContent.removeAllViews()
        val plan = update.task.plan
        for (index in 0 until plan.length()) {
            val entry = plan.optJSONObject(index) ?: continue
            when (entry.optString("type")) {
                "response" -> addCard("HOPE", entry.optString("text"))
                "action" -> addCard(
                    "Proposed device action",
                    "${entry.optString("tool_name").replace('_', ' ')} · ${entry.optJSONObject("parameters") ?: "{}"}",
                )
            }
        }
        if (update.events.isEmpty() && plan.length() == 0) {
            addCard("Status", "No plan is available yet. Use Refresh task to check again.")
        }
        if (update.task.state in setOf("awaiting_approval", "waiting_for_device")) {
            update.toolRequests.forEach(::addToolRequest)
        }
        if (update.task.state == "completed") {
            addCard("Result", "Task completed. Device actions report completion only after a local read-back check.")
        } else if (update.task.state == "failed") {
            addCard("Result", "Task failed or its device action could not be verified.")
        } else if (update.task.state == "cancelled") {
            addCard("Result", "Task cancelled.")
        }
    }

    private fun addToolRequest(tool: CloudAgentToolRequest) {
        val invalidReason = CloudAgentToolPolicy.validate(tool)
        val card = addCard(
            "Approval required",
            invalidReason ?: CloudAgentToolPolicy.confirmationText(tool),
        )
        if (invalidReason != null) return

        val approve = Button(this).apply {
            text = "Review and approve"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@CloudAgentTaskActivity)
                    .setTitle("Approve device action?")
                    .setMessage(CloudAgentToolPolicy.confirmationText(tool))
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Approve") { _, _ -> executeTool(tool) }
                    .show()
            }
        }
        card.addView(approve, matchWrap())
    }

    private fun executeTool(tool: CloudAgentToolRequest) {
        val id = taskId ?: return
        if (!processingCalls.add(tool.toolCallId)) return
        lifecycleScope.launch {
            try {
                val latest = repository.getTask(id)
                if (latest.task.state !in setOf("awaiting_approval", "waiting_for_device") ||
                    latest.toolRequests.none { it.toolCallId == tool.toolCallId }
                ) {
                    render(latest)
                    return@launch
                }
                val result = bridge.execute(tool)
                if (result.status == ToolResultStatus.PERMISSION_REQUIRED) {
                    statusText.text = result.result.optString(
                        "message",
                        "Grant the required Android permission, then approve this request again.",
                    )
                    return@launch
                }
                repository.submitToolResult(
                    taskId = id,
                    toolCallId = tool.toolCallId,
                    status = result.status,
                    result = result.result,
                    idempotencyKey = tool.toolCallId,
                )
                val afterResult = repository.getTask(id)
                if (afterResult.toolRequests.isEmpty() &&
                    afterResult.task.state in setOf("awaiting_approval", "waiting_for_device")
                ) {
                    repository.finishTask(id)
                }
                render(repository.getTask(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            } finally {
                processingCalls.remove(tool.toolCallId)
            }
        }
    }

    private fun cancelTask() {
        val id = taskId ?: return
        lifecycleScope.launch {
            try {
                repository.cancelTask(id)
                render(repository.getTask(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun addCard(title: String, message: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_jarvis_card)
        }
        card.addView(label(title, 15f, true), matchWrap())
        card.addView(label(message, 13f), matchWrap())
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply {
            topMargin = dp(8)
        }
        taskContent.addView(card, params)
        return card
    }

    private fun label(textValue: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(resolveColor(R.attr.themeTextPrimary, Color.WHITE))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

    private fun showError(error: Exception) {
        statusText.text = when (error) {
            is CloudAgentException -> error.message ?: "Cloud Agent request failed."
            else -> error.message?.take(240) ?: "Cloud Agent request failed."
        }
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_TASK_ID = "cloud_agent_task_id"
    }
}
