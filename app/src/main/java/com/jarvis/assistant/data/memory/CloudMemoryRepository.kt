package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import androidx.room.withTransaction
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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
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
            .put("sourceCreatedAt", java.time.Instant.ofEpochMilli(turn.timestamp).toString())
            .put("source", source)
        val ownerUid = requireEnabledOwner()
        enqueueJob(
            MemorySyncJob(
                jobId = MemorySyncPolicy.turnJobId(ownerUid, turn.id),
                ownerUid = ownerUid,
                operation = MemorySyncJob.OPERATION_SAVE_TURN,
                payload = payload.toString(),
            )
        )
    }

    suspend fun rememberNote(content: String, stableKey: String? = null) {
        val clean = content.trim()
        require(clean.isNotEmpty() && clean.length <= 20_000) {
            "Memory note must contain 1 to 20,000 characters."
        }
        val ownerUid = requireEnabledOwner()
        require(stableKey == null || stableKey.isNotBlank() && stableKey.length <= 512) {
            "Memory note sync key is invalid."
        }
        val jobId = stableKey?.let {
            UUID.nameUUIDFromBytes("$ownerUid:$it".toByteArray(StandardCharsets.UTF_8)).toString()
        } ?: UUID.randomUUID().toString()
        enqueueJob(
            MemorySyncJob(
                jobId = jobId,
                ownerUid = ownerUid,
                operation = MemorySyncJob.OPERATION_REMEMBER_NOTE,
                payload = JSONObject()
                    .put("operation", "remember")
                    .put("turnId", jobId)
                    .put("content", clean)
                    .toString(),
                matchText = clean,
            )
        )
    }

    suspend fun enqueueCoreBehaviorRule(rule: CoreBehaviorRule, action: String = "edited"): Boolean {
        require(action in setOf("created", "edited", "disabled", "reactivated")) {
            "Unsupported core behaviour history action."
        }
        if (!preferences.isCloudMemoryEnabled) return false
        val ownerUid = requireEnabledOwner()
        require(rule.ownerUid == ownerUid) {
            "Cannot sync Core Behaviour instructions for a different account."
        }
        val payload = JSONObject()
            .put("operation", "core_behavior_upsert")
            .put("eventId", UUID.randomUUID().toString())
            .put("action", action)
            .put("rule", JSONObject()
                .put("instructionId", rule.instructionId)
                .put("instructionText", rule.instructionText)
                .put("normalizedRule", rule.normalizedRule)
                .put("category", rule.category)
                .put("scope", rule.scope)
                .put("priority", rule.priority)
                .put("createdAt", rule.createdAt)
                .put("updatedAt", rule.updatedAt)
                .put("activeStatus", rule.activeStatus)
                .put("sourceConversation", rule.sourceConversation)
                .put("confirmationStatus", rule.confirmationStatus))
        val jobId = UUID.nameUUIDFromBytes(
            "$ownerUid:${rule.instructionId}:${rule.updatedAt}".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        enqueueJob(
            MemorySyncJob(
                jobId = jobId,
                ownerUid = ownerUid,
                operation = MemorySyncJob.OPERATION_CORE_BEHAVIOR,
                payload = payload.toString(),
                matchText = rule.instructionId,
            )
        )
        return true
    }

    suspend fun enqueueCoreBehaviorDelete(rule: CoreBehaviorRule): Boolean {
        return enqueueCoreBehaviorDeletion(
            CoreBehaviorDeletionEntity(
                instructionId = rule.instructionId,
                instructionSnapshot = rule.instructionText,
                deletedAt = System.currentTimeMillis(),
                ownerUid = rule.ownerUid,
            )
        )
    }

    suspend fun enqueueCoreBehaviorDeletion(
        deletion: CoreBehaviorDeletionEntity,
    ): Boolean {
        if (!preferences.isCloudMemoryEnabled) return false
        val ownerUid = requireEnabledOwner()
        require(deletion.ownerUid == ownerUid) {
            "Cannot sync Core Behaviour deletions for a different account."
        }
        val payload = JSONObject()
            .put("operation", "core_behavior_delete")
            .put(
                "eventId",
                UUID.nameUUIDFromBytes(
                    "deleted:${deletion.instructionId}:${deletion.deletedAt}".toByteArray(StandardCharsets.UTF_8)
                ).toString()
            )
            .put("rule", JSONObject()
                .put("instructionId", deletion.instructionId)
                .put("instructionText", deletion.instructionSnapshot)
                .put("updatedAt", deletion.deletedAt))
        val jobId = UUID.nameUUIDFromBytes(
            "$ownerUid:${deletion.instructionId}:delete:${deletion.deletedAt}".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val database = MemoryDatabase.getInstance(appContext)
        database.withTransaction {
            val dao = database.memorySyncDao()
            dao.cancelPendingCoreBehavior(ownerUid, deletion.instructionId)
            dao.enqueue(
                MemorySyncJob(
                jobId = jobId,
                ownerUid = ownerUid,
                operation = MemorySyncJob.OPERATION_CORE_BEHAVIOR,
                payload = payload.toString(),
                matchText = deletion.instructionId,
                )
            )
        }
        scheduleSync(ownerUid)
        return true
    }

    suspend fun fetchCoreBehaviorRules(): CloudCoreBehaviorSnapshot {
        if (!preferences.isCloudMemoryEnabled) return CloudCoreBehaviorSnapshot(emptyList(), emptyList())
        requireEnabledOwner()
        val response = invoke(JSONObject().put("operation", "core_behavior_list"))
        val instructions = response.optJSONArray("instructions")
            ?: throw CloudMemoryException("Cloud core behaviour response did not contain instructions.")
        val parsedInstructions = buildList {
            for (index in 0 until instructions.length()) {
                add(instructions.optJSONObject(index)
                    ?: throw CloudMemoryException("Cloud core behaviour response contained an invalid instruction."))
            }
        }
        val deletions = response.optJSONArray("deletedInstructions") ?: JSONArray()
        val parsedDeletions = buildList {
            for (index in 0 until deletions.length()) {
                val deletion = deletions.optJSONObject(index)
                    ?: throw CloudMemoryException("Cloud core behaviour response contained an invalid deletion event.")
                val instructionId = deletion.optString("instruction_id")
                val instructionSnapshot = deletion.optString("instruction_snapshot")
                val occurredAt = deletion.optLong("occurred_at", -1)
                if (instructionId.isBlank() || instructionSnapshot.length !in 1..2_000 || occurredAt < 0) {
                    throw CloudMemoryException("Cloud core behaviour response contained an invalid deletion event.")
                }
                UUID.fromString(instructionId)
                add(
                    CloudCoreBehaviorDeletion(
                        instructionId = instructionId,
                        instructionSnapshot = instructionSnapshot,
                        occurredAt = occurredAt,
                    )
                )
            }
        }
        return CloudCoreBehaviorSnapshot(parsedInstructions, parsedDeletions)
    }

    suspend fun enqueueForgetQuery(query: String) {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 2_000) {
            "Memory deletion query must contain 1 to 2,000 characters."
        }
        val ownerUid = requireEnabledOwner()
        val job = MemorySyncJob(
            jobId = UUID.randomUUID().toString(),
            ownerUid = ownerUid,
            operation = MemorySyncJob.OPERATION_FORGET_QUERY,
            payload = JSONObject().put("query", clean).toString(),
        )
        val database = MemoryDatabase.getInstance(appContext)
        database.withTransaction {
            database.memorySyncDao().cancelPendingNotesMatching(ownerUid, clean)
            database.memorySyncDao().enqueue(job)
        }
        scheduleSync(ownerUid)
    }

    suspend fun schedulePendingSync() {
        val ownerUid = LicenseManager.currentUser(appContext)?.uid ?: return
        if (MemoryDatabase.getInstance(appContext).memorySyncDao().pendingCount(ownerUid) > 0) {
            scheduleSync(ownerUid)
        }
    }

    suspend fun retryLocalSyncJobs(): Int {
        val ownerUid = requireEnabledOwner()
        val dao = MemoryDatabase.getInstance(appContext).memorySyncDao()
        val failed = dao.failedCount(ownerUid)
        dao.resetFailures(ownerUid)
        if (dao.pendingCount(ownerUid) > 0) scheduleSync(ownerUid)
        return failed
    }

    suspend fun getLocalSyncStatus(ownerUid: String): LocalMemorySyncStatus {
        val dao = MemoryDatabase.getInstance(appContext).memorySyncDao()
        return LocalMemorySyncStatus(
            pending = dao.pendingCount(ownerUid),
            failed = dao.failedCount(ownerUid),
            lastError = dao.latestError(ownerUid),
        )
    }

    internal suspend fun processOutboxJob(job: MemorySyncJob) {
        val ownerUid = requireCurrentOwner()
        if (ownerUid != job.ownerUid) {
            throw CloudMemoryException("Cloud sync is paused for this account.", 403)
        }
        if (job.operation != MemorySyncJob.OPERATION_CLEAR_ALL &&
            (!preferences.isCloudMemoryEnabled || preferences.cloudMemoryOwnerId != ownerUid)
        ) {
            throw CloudMemoryException("Cloud memory is paused for this account.", 403)
        }
        when (job.operation) {
            MemorySyncJob.OPERATION_REMEMBER_NOTE,
            MemorySyncJob.OPERATION_SAVE_TURN -> invoke(JSONObject(job.payload))
            MemorySyncJob.OPERATION_FORGET_QUERY ->
                deleteMatchingCloudMemories(JSONObject(job.payload).getString("query"))
            MemorySyncJob.OPERATION_CLEAR_ALL -> invoke(JSONObject(job.payload))
            MemorySyncJob.OPERATION_CORE_BEHAVIOR -> invoke(JSONObject(job.payload))
            else -> throw CloudMemoryException("Unsupported local memory sync operation.", 400)
        }
    }

    suspend fun backUpHistory(turns: List<ChatTurn>) {
        val currentUserId = LicenseManager.currentUser(appContext)?.uid
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to back up history.")
        if (!preferences.isCloudMemoryEnabled || preferences.cloudMemoryOwnerId != currentUserId) {
            throw CloudMemoryException("Cloud memory is paused or has not been enabled for this HOPE account.")
        }
        val eligibleTurns = turns
            .filter {
                it.ownerUid == currentUserId &&
                    it.userTranscript.isNotBlank() &&
                    it.jarvisResponse.isNotBlank()
            }
        eligibleTurns
            .chunked(HISTORY_BATCH_SIZE)
            .forEachIndexed { batchIndex, batch ->
                val values = JSONArray()
                batch.forEachIndexed { index, turn ->
                    values.put(
                        JSONObject()
                            .put("turnId", turn.id)
                            .put("userText", turn.userTranscript)
                            .put("assistantText", turn.jarvisResponse)
                            .put("sourceCreatedAt", java.time.Instant.ofEpochMilli(turn.timestamp).toString())
                            .put("sourceTurnOrder", batchIndex * HISTORY_BATCH_SIZE + index)
                    )
                }
                val batchId = batch.joinToString("|") { it.id }
                enqueueJob(
                    MemorySyncJob(
                        jobId = MemorySyncPolicy.turnJobId(currentUserId, "backup:$batchId"),
                        ownerUid = currentUserId,
                        operation = MemorySyncJob.OPERATION_SAVE_TURN,
                        payload = JSONObject()
                            .put("operation", "save_turns")
                            .put("turns", values)
                            .toString(),
                    )
                )
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
            val facts = result.optJSONArray("facts") ?: JSONArray()
            for (index in 0 until facts.length()) {
                val fact = facts.optJSONObject(index) ?: continue
                val content = fact.optString("content").trim()
                if (content.isNotEmpty()) {
                    val provenance = buildString {
                        fact.optString("source_type").takeIf(String::isNotBlank)?.let { append(it) }
                        fact.optString("source_created_at").takeIf(String::isNotBlank)
                            ?.let { if (isNotEmpty()) append("; "); append("source time $it") }
                        fact.optString("source_conversation_id").takeIf(String::isNotBlank)
                            ?.let { if (isNotEmpty()) append("; "); append("conversation $it") }
                        fact.optString("source_message_id").takeIf(String::isNotBlank)
                            ?.let { if (isNotEmpty()) append("; "); append("message $it") }
                        fact.optString("verification_status").takeIf(String::isNotBlank)
                            ?.let { if (isNotEmpty()) append("; "); append(it) }
                    }
                    appendLine("- Retrieved structured fact${if (provenance.isNotEmpty()) " ($provenance)" else ""}: ${content.take(800)}")
                }
            }
            val messages = result.optJSONArray("messages") ?: JSONArray()
            if (messages.length() > 0) {
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    val content = message.optString("content").trim()
                    if (content.isEmpty()) continue
                    val role = message.optString("role").takeIf(String::isNotBlank) ?: "message"
                    val createdAt = message.optString("createdAt").takeIf(String::isNotBlank)
                    val timestampLabel = when (message.optString("timestampOrigin")) {
                        "client_recorded" -> createdAt?.let { "recorded at $it" }
                        "server_ingested" -> createdAt?.let { "backed up at $it" }
                        "imported_unverified" -> createdAt?.let { "imported without source verification at $it" }
                        else -> createdAt?.let { "timestamp $it" }
                    }
                    val conversationId = message.optString("conversationId")
                        .takeIf(String::isNotBlank)
                        ?.let { " · conversation $it" }
                        .orEmpty()
                    val messageId = message.optString("sourceMessageId")
                        .takeIf(String::isNotBlank)
                        ?.let { " · message $it" }
                        .orEmpty()
                    val turnId = message.optString("turnId")
                        .takeIf(String::isNotBlank)
                        ?.let { " · turn $it" }
                        .orEmpty()
                    appendLine("- Historical source ${if (timestampLabel != null || conversationId.isNotEmpty() || messageId.isNotEmpty()) "(${timestampLabel.orEmpty()}$conversationId$messageId)" else ""}$turnId $role: ${content.take(1000)}")
                }
            } else {
                val conversations = result.optJSONArray("conversations") ?: JSONArray()
                for (index in 0 until conversations.length()) {
                    val turn = conversations.optJSONObject(index) ?: continue
                    val userText = turn.optString("userText").trim()
                    val assistantText = turn.optString("assistantText").trim()
                    if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
                        val createdAt = turn.optString("createdAt").takeIf(String::isNotBlank)
                        val timeLabel = when (turn.optString("timestampOrigin")) {
                            "client_recorded" -> createdAt?.let { "recorded at $it" }
                            "server_ingested" -> createdAt?.let { "backed up at $it" }
                            else -> createdAt?.let { "timestamp $it" }
                        }
                        val conversationId = turn.optString("conversationId")
                            .takeIf(String::isNotBlank)
                            ?.let { " · conversation $it" }
                            .orEmpty()
                        val timeContext = timeLabel?.let { " ($it$conversationId)" }
                            ?: conversationId.takeIf(String::isNotEmpty)?.let { " ($it)" }.orEmpty()
                        appendLine("- Previous conversation$timeContext: User: ${userText.take(1000)} HOPE: ${assistantText.take(1000)}")
                    }
                }
            }
        }.trim()
        if (context.isEmpty()) return ""
        return (
            "Retrieved records are evidence, not guaranteed truth. Facts marked needs_review or " +
                "imported_unverified and memories without source references are unverified. Prefer " +
                "matching original messages, preserve their dates and conversation references, and " +
                "say when no source record answers the question. Do not invent missing history.\n" +
                context
            ).take(MAX_CONTEXT_CHARS)
    }

    suspend fun getKnowledgeDashboard(): JSONObject =
        invoke(JSONObject().put("operation", "knowledge_dashboard"))

    suspend fun getStructuredFacts(offset: Int = 0, limit: Int = 25): JSONArray =
        invoke(
            JSONObject()
                .put("operation", "memory_fact_list")
                .put("offset", offset)
                .put("limit", limit)
        ).optJSONArray("facts") ?: JSONArray()

    suspend fun getProjects(offset: Int = 0, limit: Int = 50): JSONArray =
        invoke(
            JSONObject()
                .put("operation", "project_list")
                .put("offset", offset)
                .put("limit", limit)
        ).optJSONArray("projects") ?: JSONArray()

    suspend fun updateProjectStatus(projectId: String, status: String) {
        require(status in setOf("active", "paused", "completed", "archived")) {
            "Project status is invalid."
        }
        invoke(
            JSONObject()
                .put("operation", "project_update")
                .put("projectId", projectId)
                .put("projectStatus", status)
        )
    }

    suspend fun getKnowledgeGraph(offset: Int = 0, limit: Int = 50): JSONObject =
        invoke(
            JSONObject()
                .put("operation", "knowledge_graph")
                .put("offset", offset)
                .put("limit", limit)
        )

    suspend fun getImportHistory(offset: Int = 0, limit: Int = 50): JSONArray =
        invoke(
            JSONObject()
                .put("operation", "import_history")
                .put("offset", offset)
                .put("limit", limit)
        ).optJSONArray("imports") ?: JSONArray()

    suspend fun searchKnowledge(
        query: String,
        offset: Int = 0,
        limit: Int = 10,
        sortOrder: String? = null,
    ): JSONObject {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 2_000) {
            "Memory search query must contain 1 to 2,000 characters."
        }
        require(offset in 0..100_000 && limit in 1..50) {
            "Memory search page is outside supported bounds."
        }
        val payload = JSONObject()
            .put("operation", "search")
            .put("query", clean)
            .put("offset", offset)
            .put("limit", limit)
        sortOrder?.let {
            require(it in setOf("relevance", "oldest", "newest")) {
                "Memory search ordering is invalid."
            }
            payload.put("sortOrder", it)
        }
        return invoke(payload)
    }

    suspend fun correctStructuredMemory(memoryId: String, content: String) {
        val clean = content.trim()
        require(clean.isNotEmpty() && clean.length <= 20_000) {
            "Corrected memory must contain 1 to 20,000 characters."
        }
        invoke(
            JSONObject()
                .put("operation", "correct_fact")
                .put("memoryId", memoryId)
                .put("content", clean)
        )
    }

    suspend fun deleteStructuredMemory(memoryId: String) {
        invoke(JSONObject().put("operation", "delete_fact").put("memoryId", memoryId))
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

    suspend fun retryFailedJobs(): CloudMemoryRetryResult {
        val localJobs = retryLocalSyncJobs()
        val extractionJobs = invoke(JSONObject().put("operation", "retry_failed")).optInt("queued")
        return CloudMemoryRetryResult(localJobs, extractionJobs)
    }

    suspend fun clearAll(): String {
        val ownerUid = LicenseManager.currentUser(appContext)?.uid
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to clear cloud memory.")
        val database = MemoryDatabase.getInstance(appContext)
        database.withTransaction {
            val dao = database.memorySyncDao()
            dao.cancelPendingUploads(ownerUid)
            dao.enqueue(
                MemorySyncJob(
                    jobId = UUID.randomUUID().toString(),
                    ownerUid = ownerUid,
                    operation = MemorySyncJob.OPERATION_CLEAR_ALL,
                    payload = JSONObject().put("operation", "clear").toString(),
                )
            )
        }
        scheduleSync(ownerUid)
        return "pending"
    }

    suspend fun importHistory(openStream: () -> InputStream): JSONObject {
        val archiveDigest = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            openStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        }
        val turnCount = withContext(Dispatchers.IO) {
            openStream().use(::countHistoryTurns)
        }
        require(turnCount in 1..MAX_IMPORT_TURNS) {
            "History archive must contain 1 to $MAX_IMPORT_TURNS turns."
        }
        val expectedBatches = (turnCount + HISTORY_BATCH_SIZE - 1) / HISTORY_BATCH_SIZE
        val start = invoke(
            JSONObject()
                .put("operation", "import_start")
                .put("importId", UUID.randomUUID().toString())
                .put("archiveSha256", archiveDigest)
                .put("expectedBatches", expectedBatches)
        )
        if (start.optString("status") == "completed") {
            return JSONObject()
                .put("status", "already_completed")
                .put("duplicate", true)
                .put("importedTurns", start.optInt("imported_turns"))
                .put("rejectedTurns", start.optInt("rejected_turns"))
        }
        val importId = start.optString("import_id").takeIf(String::isNotBlank)
            ?: throw CloudMemoryException("History import did not return an import ID.")
        val resumeBatch = start.optInt("completed_batches", 0)
        var batchIndex = resumeBatch
        var turnIndex = 0
        val batch = ArrayList<JSONObject>(HISTORY_BATCH_SIZE)

        withContext(Dispatchers.IO) {
            openStream().use { input ->
                val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
                reader.isLenient = false
                var format: String? = null
                var foundTurns = false
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "format" -> format = reader.nextString()
                        "turns", "hope_conversation_turns" -> {
                            if (foundTurns) throw CloudMemoryException("History archive contains multiple turn lists.")
                            foundTurns = true
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val importedTurn = readImportedTurn(reader, turnIndex)
                                if (turnIndex >= resumeBatch * HISTORY_BATCH_SIZE) {
                                    batch += importedTurn
                                    if (batch.size == HISTORY_BATCH_SIZE) {
                                        uploadImportBatch(importId, batchIndex, batch)
                                        batch.clear()
                                        batchIndex++
                                    }
                                }
                                turnIndex++
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                reader.close()
                if (!foundTurns || format !in SUPPORTED_IMPORT_FORMATS) {
                    throw CloudMemoryException(
                        "Unsupported archive. Use a HOPE memory export or a hope-history-v1 JSON file."
                    )
                }
                if (batch.isNotEmpty()) {
                    uploadImportBatch(importId, batchIndex, batch)
                    batch.clear()
                }
                if (turnIndex != turnCount) {
                    throw CloudMemoryException("History archive changed while it was being imported.")
                }
            }
        }

        val secondDigest = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            openStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        }
        if (archiveDigest != secondDigest) {
            throw CloudMemoryException("History archive changed during import; no completion was recorded.")
        }
        return invoke(
            JSONObject()
                .put("operation", "import_finish")
                .put("importId", importId)
        )
    }

    suspend fun exportMemory(outputStream: OutputStream): CloudMemoryExportResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val counts = JSONObject()
        val exportId = UUID.randomUUID().toString()
        val digestStream = DigestOutputStream(outputStream, digest)
        val writer = JsonWriter(OutputStreamWriter(digestStream, StandardCharsets.UTF_8))
        writer.beginObject()
        writer.name("format").value(MEMORY_EXPORT_FORMAT)
        writer.name("version").value(1)
        writer.name("exportId").value(exportId)

        for (table in EXPORT_TABLES) {
            writer.name(table).beginArray()
            var offset = 0
            var recordCount = 0
            while (true) {
                val response = invoke(
                    JSONObject()
                        .put("operation", "export_page")
                        .put("table", table)
                        .put("offset", offset)
                        .put("limit", EXPORT_PAGE_SIZE)
                )
                val records = response.optJSONArray("records") ?: JSONArray()
                for (index in 0 until records.length()) {
                    val record = records.optJSONObject(index)
                        ?: throw CloudMemoryException("Memory export returned an invalid record.")
                    writer.writeJsonValue(record)
                    recordCount++
                }
                offset += records.length()
                if (records.length() < EXPORT_PAGE_SIZE) break
            }
            counts.put(table, recordCount)
            writer.endArray()
        }

        writer.endObject()
        writer.close()

        val checksum = digest.digest().toHex()
        val recorded = invoke(
            JSONObject()
                .put("operation", "export_record")
                .put("exportId", exportId)
                .put("archiveSha256", checksum)
                .put("recordCounts", counts)
        )
        return CloudMemoryExportResult(
            exportId = exportId,
            sha256 = checksum,
            recordCounts = counts,
            metadataRecorded = recorded.optBoolean("recorded"),
            verifiedBackup = recorded.optBoolean("verified"),
        )
    }

    private suspend fun uploadImportBatch(
        importId: String,
        batchIndex: Int,
        turns: List<JSONObject>,
    ) {
        val turnArray = JSONArray()
        turns.forEach(turnArray::put)
        invoke(
            JSONObject()
                .put("operation", "save_turns")
                .put("importId", importId)
                .put("batchIndex", batchIndex)
                .put("turns", turnArray)
        )
    }

    private fun countHistoryTurns(input: InputStream): Int {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        reader.isLenient = false
        var format: String? = null
        var foundTurns = false
        var turnCount = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "format" -> format = reader.nextString()
                "turns", "hope_conversation_turns" -> {
                    if (foundTurns) throw CloudMemoryException("History archive contains multiple turn lists.")
                    foundTurns = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.skipValue()
                        turnCount++
                        if (turnCount > MAX_IMPORT_TURNS) {
                            throw CloudMemoryException("History archive exceeds the supported turn limit.")
                        }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        reader.close()
        if (!foundTurns || format !in SUPPORTED_IMPORT_FORMATS) {
            throw CloudMemoryException(
                "Unsupported archive. Use a HOPE memory export or a hope-history-v1 JSON file."
            )
        }
        return turnCount
    }

    private fun readImportedTurn(reader: JsonReader, turnIndex: Int): JSONObject {
        var turnId = ""
        var sourceConversationId: String? = null
        var sourceTurnId: String? = null
        var userMessageId: String? = null
        var assistantMessageId: String? = null
        var userText = ""
        var assistantText = ""
        var sourceCreatedAt: String? = null
        var userCreatedAt: String? = null
        var assistantCreatedAt: String? = null
        var sourceTimestampOrigin: String? = null
        var userTimestampOrigin: String? = null
        var assistantTimestampOrigin: String? = null
        var turnOrder = turnIndex
        var conversationTitle: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "turnId", "turn_id" -> turnId = reader.nextStringOrNull().orEmpty()
                "sourceConversationId", "source_conversation_id", "conversationId", "conversation_id" ->
                    sourceConversationId = reader.nextStringOrNull()
                "sourceTurnId", "source_turn_id" -> sourceTurnId = reader.nextStringOrNull()
                "sourceUserMessageId", "source_user_message_id" -> userMessageId = reader.nextStringOrNull()
                "sourceAssistantMessageId", "source_assistant_message_id" -> assistantMessageId = reader.nextStringOrNull()
                "userText", "user_text" -> userText = reader.nextStringOrNull().orEmpty()
                "assistantText", "assistant_text" -> assistantText = reader.nextStringOrNull().orEmpty()
                "sourceCreatedAt", "source_created_at" -> sourceCreatedAt = reader.nextStringOrNull()
                "sourceUserCreatedAt", "source_user_created_at" -> userCreatedAt = reader.nextStringOrNull()
                "sourceAssistantCreatedAt", "source_assistant_created_at" -> assistantCreatedAt = reader.nextStringOrNull()
                "sourceTimestampOrigin", "source_timestamp_origin" ->
                    sourceTimestampOrigin = reader.nextStringOrNull()
                "sourceUserTimestampOrigin", "source_user_timestamp_origin" ->
                    userTimestampOrigin = reader.nextStringOrNull()
                "sourceAssistantTimestampOrigin", "source_assistant_timestamp_origin" ->
                    assistantTimestampOrigin = reader.nextStringOrNull()
                "sourceTurnOrder", "source_turn_order" -> {
                    turnOrder = try {
                        reader.nextInt()
                    } catch (_: NumberFormatException) {
                        throw CloudMemoryException("History archive contains an invalid turn order.")
                    }
                }
                "sourceConversationTitle", "source_conversation_title" ->
                    conversationTitle = reader.nextStringOrNull()
                else -> {
                    if (name.isBlank()) throw CloudMemoryException("History archive has an invalid field.")
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        if (userText.isBlank() || assistantText.isBlank() ||
            userText.length > 20_000 || assistantText.length > 20_000) {
            throw CloudMemoryException("History archive contains an empty or oversized conversation turn.")
        }
        val validTurnId = runCatching { UUID.fromString(turnId).toString() }.getOrNull()
        val stableTurnId = validTurnId ?: UUID.nameUUIDFromBytes(
            "${sourceConversationId.orEmpty()}|${sourceTurnId.orEmpty()}|$turnIndex".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        return JSONObject()
            .put("turnId", stableTurnId)
            .put("userText", userText)
            .put("assistantText", assistantText)
            .put("sourceCreatedAt", sourceCreatedAt ?: JSONObject.NULL)
            .put("sourceUserCreatedAt", userCreatedAt ?: JSONObject.NULL)
            .put("sourceAssistantCreatedAt", assistantCreatedAt ?: JSONObject.NULL)
            .put("sourceTimestampOrigin", sourceTimestampOrigin ?: "imported_unverified")
            .put("sourceUserTimestampOrigin", userTimestampOrigin ?: sourceTimestampOrigin ?: "imported_unverified")
            .put("sourceAssistantTimestampOrigin", assistantTimestampOrigin ?: sourceTimestampOrigin ?: "imported_unverified")
            .put("sourceConversationId", sourceConversationId ?: JSONObject.NULL)
            .put("sourceTurnId", sourceTurnId ?: JSONObject.NULL)
            .put("sourceUserMessageId", userMessageId ?: JSONObject.NULL)
            .put("sourceAssistantMessageId", assistantMessageId ?: JSONObject.NULL)
            .put("sourceTurnOrder", turnOrder)
            .put("sourceConversationTitle", conversationTitle ?: JSONObject.NULL)
    }

    private fun JsonReader.nextStringOrNull(): String? {
        return when (peek()) {
            android.util.JsonToken.NULL -> {
                nextNull()
                null
            }
            android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> nextString()
            else -> throw CloudMemoryException("History archive contains an invalid text field.")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun JsonWriter.writeJsonValue(value: Any?): JsonWriter {
        when (value) {
            null, JSONObject.NULL -> nullValue()
            is JSONObject -> {
                beginObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    name(key)
                    writeJsonValue(value.get(key))
                }
                endObject()
            }
            is JSONArray -> {
                beginArray()
                for (index in 0 until value.length()) writeJsonValue(value.get(index))
                endArray()
            }
            is Boolean -> value(value)
            is Number -> value(value)
            else -> value(value.toString())
        }
        return this
    }

    private suspend fun enqueueJob(job: MemorySyncJob) {
        MemoryDatabase.getInstance(appContext).memorySyncDao().enqueue(job)
        scheduleSync(job.ownerUid)
    }

    private fun scheduleSync(ownerUid: String) {
        try {
            MemorySyncScheduler.enqueue(appContext, ownerUid)
        } catch (error: Exception) {
            throw CloudMemoryException(
                "Memory was saved locally, but background cloud sync could not be scheduled.",
                cause = error,
                queuedLocally = true,
            )
        }
    }

    private fun requireEnabledOwner(): String {
        val ownerUid = requireCurrentOwner()
        if (!preferences.isCloudMemoryEnabled || preferences.cloudMemoryOwnerId != ownerUid) {
            throw CloudMemoryException("Cloud memory is paused or has not been enabled for this HOPE account.")
        }
        return ownerUid
    }

    private fun requireCurrentOwner(): String {
        try {
            config.requireConfigured()
        } catch (error: IllegalStateException) {
            throw CloudMemoryException(error.message ?: "Cloud memory is not configured.", cause = error)
        }
        val user = LicenseManager.currentUser(appContext)
            ?: throw CloudMemoryException("Sign in to your existing HOPE account to use cloud memory.")
        return user.uid
    }

    private suspend fun deleteMatchingCloudMemories(rawQuery: String) {
        val query = rawQuery.trim()
        require(query.isNotEmpty() && query.length <= 2_000) {
            "Memory deletion query must contain 1 to 2,000 characters."
        }

        val structuredIds = mutableListOf<String>()
        var offset = 0
        while (true) {
            val facts = searchKnowledge(query, offset = offset, limit = 50)
                .optJSONArray("facts") ?: JSONArray()
            for (index in 0 until facts.length()) {
                val fact = facts.optJSONObject(index) ?: continue
                val content = fact.optString("content")
                if (content.contains(query, ignoreCase = true)) {
                    fact.optString("memory_id")
                        .takeIf(String::isNotBlank)
                        ?.let(structuredIds::add)
                }
            }
            if (facts.length() < 50) break
            offset += facts.length()
            if (offset > 100_000) {
                throw CloudMemoryException("Cloud memory search exceeded the supported deletion range.", 400)
            }
        }
        structuredIds.distinct().forEach { memoryId ->
            deleteIfPresent { deleteStructuredMemory(memoryId) }
        }

        repeat(MAX_MEM0_DELETE_PASSES) {
            val memories = searchCloudMemories(query)
            val matches = memories.filter { it.text.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) {
                if (memories.size >= MEM0_SEARCH_LIMIT) {
                    throw CloudMemoryException(
                        "Mem0 returned a full result page without enough evidence to verify all matching deletions.",
                        409,
                    )
                }
                return
            }
            matches.forEach { memory ->
                deleteIfPresent { deleteCloudMemory(memory.id) }
            }
        }
        throw CloudMemoryException(
            "Mem0 deletion is still in progress and will be retried.",
            503,
        )
    }

    private suspend fun deleteIfPresent(delete: suspend () -> Unit) {
        try {
            delete()
        } catch (error: CloudMemoryException) {
            if (error.httpCode != 404) throw error
        }
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
        private const val MEM0_SEARCH_LIMIT = 20
        private const val MAX_MEM0_DELETE_PASSES = 20
        private const val HISTORY_BATCH_SIZE = 10
        private const val MAX_IMPORT_TURNS = 1_000_000
        private const val EXPORT_PAGE_SIZE = 200
        private const val MEMORY_EXPORT_FORMAT = "hope-memory-export-v1"
        private val SUPPORTED_IMPORT_FORMATS = setOf("hope-history-v1", MEMORY_EXPORT_FORMAT)
        private val EXPORT_TABLES = listOf(
            "hope_conversation_turns",
            "hope_conversation_messages",
            "hope_memory_facts",
            "hope_memory_projects",
            "hope_memory_entities",
            "hope_memory_relationships",
            "hope_memory_summaries",
        )
    }
}

data class LocalMemorySyncStatus(
    val pending: Int,
    val failed: Int,
    val lastError: String?,
)

data class CloudCoreBehaviorSnapshot(
    val instructions: List<JSONObject>,
    val deletedInstructions: List<CloudCoreBehaviorDeletion>,
)

data class CloudCoreBehaviorDeletion(
    val instructionId: String,
    val instructionSnapshot: String,
    val occurredAt: Long,
)

data class CloudMemoryRetryResult(
    val localSyncJobs: Int,
    val extractionJobs: Int,
)

data class CloudMemoryExportResult(
    val exportId: String,
    val sha256: String,
    val recordCounts: JSONObject,
    val metadataRecorded: Boolean,
    val verifiedBackup: Boolean,
)

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
    val queuedLocally: Boolean = false,
) : IllegalStateException(message, cause)
