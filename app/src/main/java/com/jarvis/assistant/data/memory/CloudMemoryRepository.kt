package com.jarvis.assistant.data.memory

import android.content.Context
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.supabase.SupabasePublicConfig
import com.jarvis.assistant.data.supabase.SupabaseConfiguration
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
import java.util.concurrent.TimeUnit

class CloudMemoryRepository(
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

    suspend fun saveTurn(turn: ChatTurn, source: String) {
        require(source == "chat" || source == "live_voice") { "Unsupported memory source." }
        val payload = JSONObject()
            .put("operation", "save_turn")
            .put("turnId", turn.id)
            .put("userText", turn.userTranscript)
            .put("assistantText", turn.jarvisResponse)
            .put("source", source)
        invoke(payload)
    }

    suspend fun rememberNote(content: String) {
        val clean = content.trim()
        require(clean.isNotEmpty() && clean.length <= 20_000) {
            "Memory note must contain 1 to 20,000 characters."
        }
        invoke(
            JSONObject()
                .put("operation", "remember")
                .put("turnId", java.util.UUID.randomUUID().toString())
                .put("content", clean)
        )
    }

    suspend fun backUpHistory(turns: List<ChatTurn>) {
        val currentUserId = LicenseManager.currentUser(appContext)?.uid
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to back up history.")
        turns
            .filter {
                it.ownerUid == currentUserId &&
                    it.userTranscript.isNotBlank() &&
                    it.jarvisResponse.isNotBlank()
            }
            .takeLast(MAX_HISTORY_TURNS)
            .chunked(HISTORY_BATCH_SIZE)
            .forEach { batch ->
                val values = JSONArray()
                batch.forEach { turn ->
                    values.put(
                        JSONObject()
                            .put("turnId", turn.id)
                            .put("userText", turn.userTranscript)
                            .put("assistantText", turn.jarvisResponse)
                    )
                }
                invoke(JSONObject().put("operation", "save_turns").put("turns", values))
            }
    }

    suspend fun search(query: String): String {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 2_000) {
            "Memory search query must contain 1 to 2,000 characters."
        }
        val result = invoke(JSONObject().put("operation", "search").put("query", clean))
        val context = buildString {
            val memories = result.optJSONArray("memories") ?: JSONArray()
            for (index in 0 until memories.length()) {
                val memory = memories.optJSONObject(index)?.optString("memory").orEmpty().trim()
                if (memory.isNotEmpty()) appendLine("- Remembered: ${memory.take(800)}")
            }
            val conversations = result.optJSONArray("conversations") ?: JSONArray()
            for (index in 0 until conversations.length()) {
                val turn = conversations.optJSONObject(index) ?: continue
                val userText = turn.optString("userText").trim()
                val assistantText = turn.optString("assistantText").trim()
                if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
                    appendLine("- Previous conversation: User: ${userText.take(1000)} HOPE: ${assistantText.take(1000)}")
                }
            }
        }.trim()
        return context.take(MAX_CONTEXT_CHARS)
    }

    suspend fun searchCloudMemories(query: String): List<CloudMemoryItem> {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 2_000) {
            "Memory search query must contain 1 to 2,000 characters."
        }
        val result = invoke(JSONObject().put("operation", "memory_search").put("query", clean))
        val memories = result.optJSONArray("memories") ?: JSONArray()
        return buildList {
            for (index in 0 until memories.length()) {
                val memory = memories.optJSONObject(index) ?: continue
                val id = memory.optString("id").takeIf(String::isNotBlank) ?: continue
                val text = memory.optString("memory").trim()
                if (text.isNotEmpty()) {
                    add(
                        CloudMemoryItem(
                            id = id,
                            text = text,
                            score = if (memory.has("score")) memory.optDouble("score") else null,
                            createdAt = memory.optString("created_at").takeIf(String::isNotBlank),
                        )
                    )
                }
            }
        }
    }

    suspend fun updateCloudMemory(memoryId: String, text: String) {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 2_000) {
            "Updated memory must contain 1 to 2,000 characters."
        }
        invoke(
            JSONObject()
                .put("operation", "update_memory")
                .put("memoryId", memoryId)
                .put("memory", clean)
        )
    }

    suspend fun deleteCloudMemory(memoryId: String) {
        invoke(JSONObject().put("operation", "delete_memory").put("memoryId", memoryId))
    }

    suspend fun getSyncStatus(): CloudMemorySyncStatus {
        val userId = LicenseManager.currentUser(appContext)?.uid
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to check cloud memory.")
        val enabledForUser = preferences.isCloudMemoryEnabled && preferences.cloudMemoryOwnerId == userId
        val result = invoke(
            JSONObject()
                .put("operation", "status")
                .put("cloudMemoryEnabled", enabledForUser)
        )
        val counts = result.optJSONObject("extractionCounts")
        return CloudMemorySyncStatus(
            authenticated = result.optBoolean("authenticated"),
            mem0Configured = result.optBoolean("mem0Configured"),
            mem0KeyStatus = result.optString("mem0KeyStatus", "unverified"),
            queued = counts?.optInt("queued") ?: 0,
            processing = counts?.optInt("processing") ?: 0,
            pending = counts?.optInt("pending") ?: 0,
            retryWait = counts?.optInt("retryWait") ?: 0,
            failed = counts?.optInt("failed") ?: 0,
            succeeded = counts?.optInt("succeeded") ?: 0,
            lastExtractionAttemptAt = result.optString("lastExtractionAttemptAt").takeIf(String::isNotBlank),
            lastSuccessfulExtractionAt = result.optString("lastSuccessfulExtractionAt").takeIf(String::isNotBlank),
            lastErrorCategory = result.optString("lastErrorCategory").takeIf(String::isNotBlank),
            lastRetrievalAt = result.optString("lastRetrievalAt").takeIf(String::isNotBlank),
            lastMem0ResultCount = result.optInt("lastMem0ResultCount"),
            lastTranscriptResultCount = result.optInt("lastTranscriptResultCount"),
            lastDeleteStatus = result.optString("lastDeleteStatus", "none"),
        )
    }

    suspend fun pausePendingJobs() {
        invoke(JSONObject().put("operation", "pause"))
    }

    suspend fun retryFailedJobs(): Int {
        return invoke(JSONObject().put("operation", "retry_failed")).optInt("queued")
    }

    suspend fun clearAll(): String {
        return invoke(JSONObject().put("operation", "clear"))
            .optString("mem0DeletionStatus", "unknown")
    }

    private suspend fun invoke(payload: JSONObject): JSONObject {
        config.requireConfigured()
        val user = LicenseManager.currentUser(appContext)
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to use cloud memory.")
        if (payload.optString("operation") !in setOf("clear", "pause", "status") &&
            (!preferences.isCloudMemoryEnabled || preferences.cloudMemoryOwnerId != user.uid)
        ) {
            throw CloudMemoryException("Cloud memory is paused or has not been enabled for this HOPE account.")
        }
        val token = user.getIdToken(false).await().token
            ?.takeIf(String::isNotBlank)
            ?: throw CloudMemoryException("HOPE could not obtain a Firebase sign-in token.")
        val request = Request.Builder()
            .url("${config.projectUrl}/functions/v1/hope-memory")
            .header("apikey", config.publishableKey)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    val body = try {
                        JSONObject(responseText)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    if (!response.isSuccessful) {
                        val message = body.optString("error")
                            .ifBlank { "Cloud memory request failed (HTTP ${response.code})." }
                        throw CloudMemoryException(message.take(240), response.code)
                    }
                    body
                }
            } catch (error: IOException) {
                throw CloudMemoryException(
                    "Could not reach HOPE cloud memory. Check your network connection.",
                    cause = error,
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_CONTEXT_CHARS = 6_000
        private const val MAX_HISTORY_TURNS = 100
        private const val HISTORY_BATCH_SIZE = 10
    }
}

data class CloudMemoryItem(
    val id: String,
    val text: String,
    val score: Double? = null,
    val createdAt: String? = null,
)

data class CloudMemorySyncStatus(
    val authenticated: Boolean,
    val mem0Configured: Boolean,
    val mem0KeyStatus: String,
    val queued: Int,
    val processing: Int,
    val pending: Int,
    val retryWait: Int,
    val failed: Int,
    val succeeded: Int,
    val lastExtractionAttemptAt: String?,
    val lastSuccessfulExtractionAt: String?,
    val lastErrorCategory: String?,
    val lastRetrievalAt: String?,
    val lastMem0ResultCount: Int,
    val lastTranscriptResultCount: Int,
    val lastDeleteStatus: String,
)

class CloudMemoryException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
