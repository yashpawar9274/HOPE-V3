package com.jarvis.assistant.data.agent

import android.content.Context
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.supabase.SupabaseConfiguration
import com.jarvis.assistant.data.supabase.SupabasePublicConfig
import com.jarvis.assistant.license.LicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudAgentRepository(
    context: Context,
    private val config: SupabasePublicConfig = SupabaseConfiguration.publicClient,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)

    fun isEnabledForCurrentUser(): Boolean {
        val userId = LicenseManager.currentUser(appContext)?.uid ?: return false
        return preferences.isCloudAgentEnabled && preferences.cloudAgentOwnerId == userId
    }

    suspend fun setEnabled(enabled: Boolean) {
        val userId = LicenseManager.currentUser(appContext)?.uid
            ?: throw CloudAgentException("Sign in to your existing HOPE account to configure Cloud Agent.")
        if (enabled) {
            invoke(JSONObject().put("operation", "consent").put("enabled", true))
            preferences.cloudAgentOwnerId = userId
            preferences.isCloudAgentEnabled = true
        } else {
            invoke(JSONObject().put("operation", "consent").put("enabled", false))
            preferences.isCloudAgentEnabled = false
        }
    }

    suspend fun createTask(
        objective: String,
        sessionId: String? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): CloudAgentTask {
        val cleanObjective = objective.trim()
        require(cleanObjective.isNotEmpty() && cleanObjective.length <= MAX_OBJECTIVE_LENGTH) {
            "Task objective must contain 1 to $MAX_OBJECTIVE_LENGTH characters."
        }
        require(isUuid(idempotencyKey)) { "Task idempotency key is invalid." }
        if (sessionId != null) require(isUuid(sessionId)) { "Agent session ID is invalid." }
        val request = JSONObject()
            .put("operation", "create_task")
            .put("objective", cleanObjective)
            .put("idempotencyKey", idempotencyKey)
        sessionId?.let { request.put("sessionId", it) }
        return parseTask(invoke(request))
    }

    suspend fun getTask(
        taskId: String,
        afterSequence: Long = 0,
        limit: Int = 50,
    ): CloudAgentTaskUpdate {
        require(isUuid(taskId)) { "Task ID is invalid." }
        require(afterSequence >= 0 && limit in 1..100) { "Task event cursor or limit is invalid." }
        val response = invoke(
            JSONObject()
                .put("operation", "get_task")
                .put("taskId", taskId)
                .put("afterSequence", afterSequence)
                .put("limit", limit)
        )
        val task = response.optJSONObject("task")
            ?: throw CloudAgentException("Cloud task response did not contain a task.")
        val events = response.optJSONArray("events") ?: JSONArray()
        val requests = response.optJSONArray("toolRequests") ?: JSONArray()
        return CloudAgentTaskUpdate(
            task = parseTask(task),
            events = buildList {
                for (index in 0 until events.length()) {
                    events.optJSONObject(index)?.let { event ->
                        add(
                            CloudAgentEvent(
                                sequence = event.optLong("sequence"),
                                type = event.optString("event_type"),
                                payload = event.optJSONObject("payload") ?: JSONObject(),
                                createdAt = event.optString("created_at"),
                            )
                        )
                    }
                }
            },
            toolRequests = buildList {
                for (index in 0 until requests.length()) {
                    requests.optJSONObject(index)?.let { tool ->
                        add(
                            CloudAgentToolRequest(
                                toolCallId = tool.optString("tool_call_id"),
                                toolName = tool.optString("tool_name"),
                                parameters = tool.optJSONObject("parameters") ?: JSONObject(),
                                permissionScope = tool.optString("permission_scope"),
                                riskCategory = tool.optString("risk_category"),
                                expiresAt = tool.optString("expires_at"),
                            )
                        )
                    }
                }
            },
        )
    }

    suspend fun cancelTask(taskId: String): JSONObject = updateTask(taskId, "cancel_task")

    suspend fun pauseTask(taskId: String): JSONObject = updateTask(taskId, "pause_task")

    suspend fun resumeTask(taskId: String): JSONObject = updateTask(taskId, "resume_task")

    suspend fun finishTask(taskId: String): JSONObject = updateTask(taskId, "finish_task")

    suspend fun submitToolResult(
        taskId: String,
        toolCallId: String,
        status: ToolResultStatus,
        result: JSONObject,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): JSONObject {
        require(isUuid(taskId) && isUuid(toolCallId) && isUuid(idempotencyKey)) {
            "Task, tool-call, or idempotency ID is invalid."
        }
        require(result.toString().length <= MAX_RESULT_LENGTH) { "Tool result is too large." }
        return invoke(
            JSONObject()
                .put("operation", "submit_tool_result")
                .put("taskId", taskId)
                .put("toolCallId", toolCallId)
                .put("resultStatus", status.name)
                .put("result", result)
                .put("idempotencyKey", idempotencyKey)
        )
    }

    private suspend fun updateTask(taskId: String, operation: String): JSONObject {
        require(isUuid(taskId)) { "Task ID is invalid." }
        return invoke(JSONObject().put("operation", operation).put("taskId", taskId))
    }

    private suspend fun invoke(payload: JSONObject): JSONObject {
        config.requireConfigured()
        val user = LicenseManager.currentUser(appContext)
            ?: throw CloudAgentException("Sign in to your existing HOPE account to use Cloud Agent.")
        if (payload.optString("operation") != "consent" &&
            (!preferences.isCloudAgentEnabled || preferences.cloudAgentOwnerId != user.uid)
        ) {
            throw CloudAgentException("Cloud Agent is off for this HOPE account. Enable it with explicit consent first.")
        }
        val token = user.getIdToken(false).await().token
            ?.takeIf(String::isNotBlank)
            ?: throw CloudAgentException("HOPE could not obtain a Firebase sign-in token.")
        val request = Request.Builder()
            .url("${config.projectUrl}/functions/v1/hope-agent-gateway")
            .header("apikey", config.publishableKey)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = try {
                        JSONObject(response.body?.string().orEmpty())
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    if (!response.isSuccessful) {
                        val message = body.optString("error")
                            .ifBlank { "Cloud Agent request failed (HTTP ${response.code})." }
                        throw CloudAgentException(message.take(240), response.code)
                    }
                    body
                }
            } catch (error: IOException) {
                throw CloudAgentException(
                    "Could not reach HOPE Cloud Agent. Check your network connection.",
                    cause = error,
                )
            }
        }
    }

    private fun parseTask(value: JSONObject): CloudAgentTask {
        val taskId = value.optString("task_id")
        val sessionId = value.optString("session_id")
        if (!isUuid(taskId) || !isUuid(sessionId)) {
            throw CloudAgentException("Cloud Agent returned an invalid task or session ID.")
        }
        return CloudAgentTask(
            taskId = taskId,
            sessionId = sessionId,
            objective = value.optString("objective"),
            state = value.optString("state"),
            plan = value.optJSONArray("plan") ?: JSONArray(),
            lastEventSequence = value.optLong("last_event_sequence"),
            createdAt = value.optString("created_at"),
            replayed = value.optBoolean("replayed"),
        )
    }

    private fun isUuid(value: String): Boolean =
        UUID_PATTERN.matches(value)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        const val MAX_OBJECTIVE_LENGTH = 4_000
        const val MAX_RESULT_LENGTH = 32_000
    }
}

data class CloudAgentTask(
    val taskId: String,
    val sessionId: String,
    val objective: String,
    val state: String,
    val plan: JSONArray = JSONArray(),
    val lastEventSequence: Long = 0,
    val createdAt: String = "",
    val replayed: Boolean = false,
)

data class CloudAgentEvent(
    val sequence: Long,
    val type: String,
    val payload: JSONObject,
    val createdAt: String,
)

data class CloudAgentToolRequest(
    val toolCallId: String,
    val toolName: String,
    val parameters: JSONObject,
    val permissionScope: String,
    val riskCategory: String,
    val expiresAt: String,
)

data class CloudAgentTaskUpdate(
    val task: CloudAgentTask,
    val events: List<CloudAgentEvent>,
    val toolRequests: List<CloudAgentToolRequest>,
)

enum class ToolResultStatus {
    SUCCESS,
    FAILED,
    PERMISSION_REQUIRED,
    USER_CONFIRMATION_REQUIRED,
    UNSUPPORTED,
    TIMEOUT,
    CANCELLED,
}

class CloudAgentException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
