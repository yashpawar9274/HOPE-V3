package com.jarvis.hope.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ClaimedTask(
    val userId: String,
    val taskId: String,
    val sessionId: String,
    val objective: String,
    val executionAttempts: Int,
)

class SupabaseAgentStore(
    private val baseUrl: String,
    private val serviceRoleKey: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun claimTasks(maxResults: Int): List<ClaimedTask> {
        require(maxResults in 1..10)
        val response = rpc("claim_hope_agent_tasks", mapper.createObjectNode().put("max_results", maxResults))
        require(response.isArray) { "Task claim response is invalid." }
        return response.map { row ->
            val userId = requiredText(row, "user_id")
            val taskId = requiredText(row, "task_id")
            val sessionId = requiredText(row, "session_id")
            UUID.fromString(taskId)
            UUID.fromString(sessionId)
            ClaimedTask(
                userId,
                taskId,
                sessionId,
                requiredText(row, "objective"),
                row.path("execution_attempts").takeIf(JsonNode::isIntegralNumber)?.asInt()
                    ?: throw IllegalStateException("Task claim omitted execution attempts."),
            )
        }
    }

    fun publishPlan(task: ClaimedTask, plan: AgentPlan): String {
        val planJson = mapper.createArrayNode()
        planJson.add(mapper.createObjectNode().put("type", "response").put("text", plan.response))
        val proposals = mapper.createArrayNode()
        plan.actions.forEach { action ->
            val callId = UUID.randomUUID().toString()
            val parameters = mapper.valueToTree<ObjectNode>(action.parameters)
            proposals.add(
                mapper.createObjectNode().apply {
                    put("tool_call_id", callId)
                    put("tool_name", action.toolName)
                    set<ObjectNode>("parameters", parameters)
                    put("permission_scope", action.permissionScope)
                    put("risk_category", action.riskCategory)
                },
            )
            planJson.add(
                mapper.createObjectNode().apply {
                    put("type", "action")
                    put("tool_call_id", callId)
                    put("tool_name", action.toolName)
                    set<ObjectNode>("parameters", parameters.deepCopy())
                },
            )
        }
        val body = mapper.createObjectNode().apply {
            put("owner_user_id", task.userId)
            put("target_task_id", task.taskId)
            put("expected_execution_attempt", task.executionAttempts)
            set<ArrayNode>("new_plan", planJson)
            set<ArrayNode>("new_tool_calls", proposals)
        }
        return rpc("publish_hope_agent_plan", body).asText()
    }

    fun markFailed(task: ClaimedTask, failureCode: String) {
        val query = "user_id=eq.${encode(task.userId)}&task_id=eq.${encode(task.taskId)}&state=eq.planning&cancellation_requested=eq.false&execution_attempts=eq.${task.executionAttempts}"
        val body = mapper.createObjectNode()
            .put("state", "failed")
            .put("failure_code", failureCode.take(64))
            .putNull("lease_expires_at")
            .put("completed_at", Instant.now().toString())
        val updated = request("PATCH", "/rest/v1/hope_agent_tasks?$query", body, "return=representation")
        if (updated.isArray && updated.size() == 1) {
            val event = mapper.createObjectNode().apply {
                put("owner_user_id", task.userId)
                put("target_task_id", task.taskId)
                put("new_event_type", "task.failed")
                set<ObjectNode>(
                    "new_payload",
                    mapper.createObjectNode().put("failureCode", failureCode.take(64)),
                )
            }
            rpc("append_hope_agent_event", event)
        }
    }

    private fun rpc(name: String, body: JsonNode): JsonNode =
        request("POST", "/rest/v1/rpc/$name", body)

    private fun request(
        method: String,
        path: String,
        body: JsonNode,
        prefer: String? = null,
    ): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        prefer?.let { builder.header("Prefer", it) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase agent store returned HTTP ${response.statusCode()}.")
        }
        val content = response.body()
        return if (content.isBlank()) mapper.nodeFactory.nullNode() else mapper.readTree(content)
    }

    private fun requiredText(node: JsonNode, field: String): String =
        node.path(field).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Task claim omitted $field.")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
