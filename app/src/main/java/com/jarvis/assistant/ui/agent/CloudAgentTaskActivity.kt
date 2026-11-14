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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

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
                "response" -> {
                    val model = entry.optString("model")
                    val usedFallback = entry.optBoolean("usedFallback")
                    val title = if (model.isBlank()) {
                        "HOPE"
                    } else {
                        "HOPE coding proposal · $model${if (usedFallback) " (configured fallback)" else ""}"
                    }
                    addCard(title, entry.optString("text"))
                    val questions = entry.optJSONArray("questions") ?: continue
                    for (questionIndex in 0 until questions.length()) {
                        addCard(
                            "Clarification needed",
                            questions.optString(questionIndex),
                        )
                    }
                }
                "action" -> addCard(
                    "Proposed device action",
                    "${entry.optString("tool_name").replace('_', ' ')} · ${entry.optJSONObject("parameters") ?: "{}"}",
                )
                "file_proposal" -> renderFileProposal(entry)
            }
        }
        if (update.events.isEmpty() && plan.length() == 0) {
            addCard("Status", "No plan is available yet. Use Refresh task to check again.")
        }
        if (update.task.state in setOf("awaiting_approval", "waiting_for_device")) {
            update.toolRequests.forEach { addToolRequest(update, it) }
        }
        val hasCodingResponse = (0 until plan.length()).any { index ->
            plan.optJSONObject(index)?.let { entry ->
                entry.optString("type") == "response" && entry.has("model")
            } == true
        }
        if (update.task.state == "completed" && hasCodingResponse) {
            addCard(
                "Proposal only",
                "This coding response is a proposal only. No project files were saved, executed, built, or tested.",
            )
        } else if (update.task.state == "completed") {
            addCard("Result", "Task completed. Device actions report completion only after a local read-back check.")
        } else if (update.task.state == "failed") {
            addCard("Result", taskFailureMessage(update.task.failureCode))
        } else if (update.task.state == "cancelled") {
            addCard("Result", "Task cancelled.")
        }
    }

    private fun taskFailureMessage(code: String): String = when (code) {
        "gemini_coding_timeout" ->
            "The coding assistant timed out. Check your connection and try again."
        "gemini_coding_network_error", "gemini_coding_provider_unavailable" ->
            "The coding assistant is temporarily unreachable. Check your connection and retry."
        "gemini_coding_rate_limited" ->
            "The coding assistant is busy. Please wait a moment and try again."
        "gemini_coding_authentication_failed" ->
            "The coding service is not configured correctly. Please contact support."
        "gemini_coding_model_unavailable" ->
            "The configured coding model is unavailable. Please contact support."
        "gemini_coding_invalid_output", "gemini_coding_empty_response",
        "gemini_coding_incomplete_response" ->
            "The coding assistant returned an unusable response. Please try again."
        else -> "Task failed or its device action could not be verified."
    }

    private fun renderFileProposal(entry: JSONObject) {
        val path = entry.optString("path")
        val purpose = entry.optString("purpose")
        val content = entry.optString("content")
        val preview = content.take(MAX_FILE_PREVIEW_CHARACTERS)
        val suffix = if (content.length > preview.length) "\n\nPreview truncated; open the full proposal to view the rest." else ""
        val card = addCard(
            "Suggested file · $path",
            "$purpose\n\n$preview$suffix",
        )
        if (content.length > preview.length) {
            card.addView(Button(this).apply {
                text = "View full proposal"
                setOnClickListener { showFileProposal(path, content) }
            }, matchWrap())
        }
    }

    private fun showFileProposal(path: String, content: String) {
        val source = TextView(this).apply {
            text = content
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(resolveColor(R.attr.themeTextPrimary, Color.WHITE))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(source)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(path)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun addToolRequest(update: CloudAgentTaskUpdate, tool: CloudAgentToolRequest) {
        val invalidReason = CloudAgentToolPolicy.validate(
            tool,
            expectedTaskId = update.task.taskId,
            expectedSessionId = update.task.sessionId,
        )
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
                    .setNegativeButton("Decline") { _, _ -> declineToolRequest(tool) }
                    .setPositiveButton("Approve") { _, _ -> executeTool(tool) }
                    .show()
            }
        }
        card.addView(approve, matchWrap())
    }

    private fun declineToolRequest(tool: CloudAgentToolRequest) {
        if (!processingCalls.add(tool.toolCallId)) return
        lifecycleScope.launch {
            try {
                repository.submitToolResult(
                    tool = tool,
                    status = ToolResultStatus.CANCELLED,
                    result = structuredToolResult(
                        tool = tool,
                        status = ToolResultStatus.CANCELLED,
                        details = JSONObject()
                            .put("verified", false)
                            .put("message", "The user declined this device action."),
                    ),
                )
                val latest = repository.getTask(tool.taskId)
                if (latest.toolRequests.isEmpty() &&
                    latest.task.state in setOf("awaiting_approval", "waiting_for_device")
                ) {
                    repository.finishTask(tool.taskId)
                }
                render(repository.getTask(tool.taskId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            } finally {
                processingCalls.remove(tool.toolCallId)
            }
        }
    }

    private fun executeTool(tool: CloudAgentToolRequest) {
        val id = taskId ?: return
        if (!processingCalls.add(tool.toolCallId)) return
        lifecycleScope.launch {
            try {
                val latest = repository.getTask(id)
                if (latest.task.state !in setOf("awaiting_approval", "waiting_for_device") ||
                    latest.task.taskId != tool.taskId ||
                    latest.task.sessionId != tool.sessionId ||
                    latest.toolRequests.none {
                        CloudAgentToolPolicy.matchesAuthorizedRequest(tool, it)
                    }
                ) {
                    render(latest)
                    return@launch
                }
                val result = withTimeout(
                    CloudAgentToolPolicy.contracts
                        .first { it.name == tool.toolName }
                        .timeoutMillis,
                ) {
                    withContext(Dispatchers.IO) {
                        bridge.execute(
                            tool = tool,
                            expectedTaskId = latest.task.taskId,
                            expectedSessionId = latest.task.sessionId,
                            userApproved = true,
                        )
                    }
                }
                if (result.status == ToolResultStatus.PERMISSION_REQUIRED) {
                    statusText.text = result.result.optString(
                        "message",
                        "Grant the required Android permission, then approve this request again.",
                    )
                    return@launch
                }
                repository.submitToolResult(
                    tool = tool,
                    status = result.status,
                    result = structuredToolResult(tool, result.status, result.result),
                )
                val afterResult = repository.getTask(id)
                if (afterResult.toolRequests.isEmpty() &&
                    afterResult.task.state in setOf("awaiting_approval", "waiting_for_device")
                ) {
                    repository.finishTask(id)
                }
                render(repository.getTask(id))
            } catch (_: TimeoutCancellationException) {
                try {
                    repository.submitToolResult(
                        tool = tool,
                        status = ToolResultStatus.TIMEOUT,
                        result = structuredToolResult(
                            tool = tool,
                            status = ToolResultStatus.TIMEOUT,
                            details = JSONObject()
                                .put("verified", false)
                                .put("message", "The device operation exceeded its execution timeout."),
                        ),
                    )
                    render(repository.getTask(tool.taskId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    showError(error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            } finally {
                processingCalls.remove(tool.toolCallId)
            }
        }
    }

    private fun structuredToolResult(
        tool: CloudAgentToolRequest,
        status: ToolResultStatus,
        details: JSONObject,
    ): JSONObject = JSONObject(details.toString())
        .put("schemaVersion", 1)
        .put("taskId", tool.taskId)
        .put("sessionId", tool.sessionId)
        .put("toolCallId", tool.toolCallId)
        .put("toolName", tool.toolName)
        .put("resultStatus", status.name)

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
        const val MAX_FILE_PREVIEW_CHARACTERS = 2_500
    }
}
