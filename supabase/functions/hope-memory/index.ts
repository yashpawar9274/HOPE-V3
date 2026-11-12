import {
  corsHeaders,
  jsonResponse,
  memoryDatabaseClient,
  readJsonObject,
  verifiedUser,
} from "../_shared/auth.ts";

declare const Deno: {
  serve(handler: (request: Request) => Promise<Response>): void;
  env: { get(name: string): string | undefined };
};
declare const EdgeRuntime: {
  waitUntil(promise: Promise<unknown>): void;
};

const MEM0_BASE_URL = "https://api.mem0.ai";
const TURN_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MEMORY_ID_PATTERN = TURN_ID_PATTERN;
const MAX_EXTRACTION_ATTEMPTS = 5;
const MAX_WORKER_ROUNDS = 20;

type ExtractionJob = {
  user_id: string;
  turn_id: string;
  status: string;
  attempts: number;
  mem0_event_id: string | null;
};

type MemoryRecord = {
  id: string;
  memory: string;
  metadata?: Record<string, unknown> | null;
  created_at?: string | null;
  updated_at?: string | null;
  score?: number;
  user_id?: string;
};

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function spawnJobWorker(userId: string): void {
  EdgeRuntime.waitUntil(processPendingJobs(userId));
}

async function processPendingJobs(userId: string): Promise<void> {
  const database = memoryDatabaseClient();
  for (let round = 0; round < MAX_WORKER_ROUNDS; round++) {
    try {
      const { data, error } = await database.rpc("claim_hope_memory_extraction_jobs", {
        owner_user_id: userId,
        max_results: 10,
      });
      if (error) {
        console.error("memory_job_claim_failed");
        return;
      }
      const jobs = (data ?? []) as ExtractionJob[];
      if (jobs.length === 0) return;
      for (const job of jobs) await processJob(database, job);
    } catch {
      console.error("memory_job_worker_failed");
      return;
    }

    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

async function processJob(database: ReturnType<typeof memoryDatabaseClient>, job: ExtractionJob): Promise<void> {
  const { data: currentJob, error: stateError } = await database
    .from("hope_memory_extraction_jobs")
    .select("status")
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .maybeSingle();
  if (stateError) {
    console.error("memory_job_state_check_failed");
    return;
  }
  if (currentJob?.status !== "processing") return;

  const apiKey = Deno.env.get("MEM0_API_KEY");
  if (!apiKey) {
    await failJob(database, job, "mem0_not_configured", true);
    return;
  }

  if (job.mem0_event_id) {
    await pollExtractionEvent(database, job, apiKey);
    return;
  }

  const { data: turn, error } = await database
    .from("hope_conversation_turns")
    .select("user_text,assistant_text,source")
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .maybeSingle();
  if (error || !turn) {
    await failJob(database, job, "transcript_unavailable", true);
    return;
  }

  const attempt = job.attempts + 1;
  const { error: attemptError } = await database
    .from("hope_memory_extraction_jobs")
    .update({
      attempts: attempt,
      last_attempt_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .eq("status", "processing");
  if (attemptError) {
    console.error("memory_job_attempt_update_failed");
    return;
  }

  const { data: readyJob, error: readyError } = await database
    .from("hope_memory_extraction_jobs")
    .select("status")
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .maybeSingle();
  if (readyError) {
    console.error("memory_job_state_check_failed");
    return;
  }
  if (readyJob?.status !== "processing") return;

  try {
    const response = await fetch(`${MEM0_BASE_URL}/v3/memories/add/`, {
      method: "POST",
      headers: {
        Authorization: `Token ${apiKey}`,
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      signal: AbortSignal.timeout(20_000),
      body: JSON.stringify({
        user_id: job.user_id,
        messages: [
          { role: "user", content: turn.user_text },
          { role: "assistant", content: turn.assistant_text },
        ],
        metadata: {
          source: turn.source === "manual" ? "hope_android_manual" : "hope_android",
          turn_id: job.turn_id,
        },
      }),
    });

    const payload: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const permanent = response.status === 400 || response.status === 401 ||
        response.status === 403 || response.status === 404 || response.status === 422;
      await failJob(database, { ...job, attempts: attempt }, `mem0_add_http_${response.status}`, permanent);
      return;
    }

    const eventId = isObject(payload) && typeof payload.event_id === "string"
      ? payload.event_id
      : "";
    if (!MEMORY_ID_PATTERN.test(eventId)) {
      await failJob(database, { ...job, attempts: attempt }, "mem0_add_invalid_event", true);
      return;
    }

    const status = isObject(payload) && typeof payload.status === "string"
      ? payload.status.toUpperCase()
      : "";
    if (status === "SUCCEEDED") {
      await completeJob(database, job);
      return;
    }
    if (status === "FAILED") {
      await failJob(database, { ...job, attempts: attempt }, "mem0_event_failed", false);
      return;
    }

    await database
      .from("hope_memory_extraction_jobs")
      .update({
        status: "pending",
        mem0_event_id: eventId,
        last_error_category: null,
        next_attempt_at: new Date(Date.now() + 2_000).toISOString(),
        lease_expires_at: null,
        updated_at: new Date().toISOString(),
      })
      .eq("user_id", job.user_id)
      .eq("turn_id", job.turn_id)
      .eq("status", "processing");
  } catch (error) {
    const category = error instanceof DOMException && error.name === "TimeoutError"
      ? "mem0_add_timeout"
      : "mem0_add_network_error";
    await failJob(database, { ...job, attempts: attempt }, category, false);
  }
}

async function pollExtractionEvent(
  database: ReturnType<typeof memoryDatabaseClient>,
  job: ExtractionJob,
  apiKey: string,
): Promise<void> {
  try {
    const response = await fetch(
      `${MEM0_BASE_URL}/v1/event/${encodeURIComponent(job.mem0_event_id!)}/`,
      {
        headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      },
    );
    if (!response.ok) {
      const permanent = response.status === 401 || response.status === 403 ||
        response.status === 404;
      if (permanent) {
        await failJob(database, job, `mem0_event_http_${response.status}`, true);
      } else {
        await deferEventPoll(database, job, `mem0_event_http_${response.status}`);
      }
      return;
    }

    const payload: unknown = await response.json().catch(() => null);
    const status = isObject(payload) && typeof payload.status === "string"
      ? payload.status.toUpperCase()
      : "";
    if (status === "SUCCEEDED") {
      await completeJob(database, job);
    } else if (status === "FAILED") {
      await failJob(database, job, "mem0_event_failed", false);
    } else if (status === "PENDING" || status === "RUNNING") {
      await deferEventPoll(database, job, null);
    } else {
      await failJob(database, job, "mem0_event_invalid_status", true);
    }
  } catch (error) {
    const category = error instanceof DOMException && error.name === "TimeoutError"
      ? "mem0_event_timeout"
      : "mem0_event_network_error";
    await deferEventPoll(database, job, category);
  }
}

async function deferEventPoll(
  database: ReturnType<typeof memoryDatabaseClient>,
  job: ExtractionJob,
  errorCategory: string | null,
): Promise<void> {
  await database
    .from("hope_memory_extraction_jobs")
    .update({
      status: "pending",
      last_error_category: errorCategory,
      next_attempt_at: new Date(Date.now() + 3_000).toISOString(),
      lease_expires_at: null,
      updated_at: new Date().toISOString(),
    })
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .eq("status", "processing");
}

async function completeJob(
  database: ReturnType<typeof memoryDatabaseClient>,
  job: ExtractionJob,
): Promise<void> {
  await database
    .from("hope_memory_extraction_jobs")
    .update({
      status: "succeeded",
      last_error_category: null,
      next_attempt_at: new Date().toISOString(),
      lease_expires_at: null,
      processed_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .eq("status", "processing");
}

async function failJob(
  database: ReturnType<typeof memoryDatabaseClient>,
  job: ExtractionJob,
  category: string,
  permanent: boolean,
): Promise<void> {
  const shouldRetry = !permanent && job.attempts < MAX_EXTRACTION_ATTEMPTS;
  const delaySeconds = Math.min(60, 2 ** Math.max(job.attempts, 1));
  await database
    .from("hope_memory_extraction_jobs")
    .update({
      status: shouldRetry ? "retry_wait" : "failed",
      mem0_event_id: null,
      last_error_category: category,
      next_attempt_at: new Date(Date.now() + delaySeconds * 1_000).toISOString(),
      lease_expires_at: null,
      processed_at: shouldRetry ? null : new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("user_id", job.user_id)
    .eq("turn_id", job.turn_id)
    .eq("status", "processing");
}

function hasSensitiveContent(userText: string, assistantText: string): boolean {
  const sensitivePattern = /\b(password|passcode|one[\s-]?time\s+(?:password|code|pin)|\bOTP\b|CVV|security code|credit card number|debit card number|bank account number|access token|private key)\b/i;
  return sensitivePattern.test(userText) || sensitivePattern.test(assistantText);
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return jsonResponse({ error: "Method not allowed." }, 405);
  }

  try {
    const user = await verifiedUser(request);
    if (!user) return jsonResponse({ error: "Authentication required." }, 401);

    const payload = await readJsonObject(request);
    if (!payload) return jsonResponse({ error: "Invalid JSON request." }, 400);
    const operation = payload.operation;
    const apiKey = Deno.env.get("MEM0_API_KEY");
    const database = memoryDatabaseClient();

    if (operation === "save_turn" || operation === "remember") {
      const turnId = payload.turnId;
      const userText = operation === "remember" ? payload.content : payload.userText;
      const assistantText = operation === "remember"
        ? "Saved as a HOPE long-term memory note."
        : payload.assistantText;
      const source = operation === "remember" ? "manual" : payload.source;
      if (typeof turnId !== "string" || !TURN_ID_PATTERN.test(turnId)) {
        return jsonResponse({ error: "A valid turn ID is required." }, 400);
      }
      if (typeof userText !== "string" || userText.trim().length === 0 ||
        userText.length > 20_000 || typeof assistantText !== "string" ||
        assistantText.trim().length === 0 || assistantText.length > 20_000) {
        return jsonResponse({ error: "Conversation text must be within the supported size." }, 400);
      }
      if (source !== "chat" && source !== "live_voice" && source !== "manual") {
        return jsonResponse({ error: "Unsupported conversation source." }, 400);
      }
      const cleanUserText = userText.trim();
      const cleanAssistantText = assistantText.trim();
      if (hasSensitiveContent(cleanUserText, cleanAssistantText)) {
        return jsonResponse({ saved: false, excluded: true, reason: "sensitive_content" });
      }

      const { error: saveError } = await database
        .from("hope_conversation_turns")
        .upsert(
          {
            user_id: user.id,
            turn_id: turnId,
            user_text: cleanUserText,
            assistant_text: cleanAssistantText,
            source,
          },
          { onConflict: "user_id,turn_id" },
        );
      if (saveError) return jsonResponse({ error: "Conversation transcript could not be stored." }, 502);

      const { error: jobError } = await database
        .from("hope_memory_extraction_jobs")
        .upsert(
          { user_id: user.id, turn_id: turnId, status: "queued" },
          { onConflict: "user_id,turn_id", ignoreDuplicates: true },
        );
      if (jobError) return jsonResponse({ error: "Transcript was stored, but its extraction job could not be queued." }, 502);
      const { data: job, error: statusError } = await database
        .from("hope_memory_extraction_jobs")
        .select("status")
        .eq("user_id", user.id)
        .eq("turn_id", turnId)
        .maybeSingle();
      if (statusError || !job) {
        return jsonResponse({ error: "Transcript was stored, but extraction status could not be verified." }, 502);
      }
      if (apiKey && (job.status === "queued" || job.status === "pending" || job.status === "retry_wait")) {
        spawnJobWorker(user.id);
      }
      return jsonResponse({ saved: true, transcriptStored: true, extractionStatus: job.status }, 202);
    }

    if (operation === "save_turns") {
      if (!Array.isArray(payload.turns) || payload.turns.length < 1 || payload.turns.length > 10) {
        return jsonResponse({ error: "History batches must contain 1 to 10 turns." }, 400);
      }
      const turns: Array<{
        turn_id: string;
        user_id: string;
        user_text: string;
        assistant_text: string;
        source: "chat";
      }> = [];
      let totalCharacters = 0;
      for (const value of payload.turns) {
        if (!isObject(value)) return jsonResponse({ error: "A history turn is invalid." }, 400);
        const turnId = value.turnId;
        const userText = value.userText;
        const assistantText = value.assistantText;
        if (typeof turnId !== "string" || !TURN_ID_PATTERN.test(turnId) ||
          typeof userText !== "string" || userText.trim().length === 0 ||
          typeof assistantText !== "string" || assistantText.trim().length === 0 ||
          userText.length > 20_000 || assistantText.length > 20_000) {
          return jsonResponse({ error: "A history turn has invalid or oversized text." }, 400);
        }
        totalCharacters += userText.length + assistantText.length;
        if (totalCharacters > 400_000) {
          return jsonResponse({ error: "History batch exceeds the supported size." }, 400);
        }
        if (hasSensitiveContent(userText, assistantText)) continue;
        turns.push({
          turn_id: turnId,
          user_id: user.id,
          user_text: userText.trim(),
          assistant_text: assistantText.trim(),
          source: "chat",
        });
      }
      if (turns.length === 0) {
        return jsonResponse({ turnsStored: 0, excludedSensitiveTurns: payload.turns.length });
      }

      const { error: saveError } = await database
        .from("hope_conversation_turns")
        .upsert(turns, { onConflict: "user_id,turn_id" });
      if (saveError) return jsonResponse({ error: "Previous conversation history could not be stored." }, 502);
      const jobs = turns.map((turn) => ({
        user_id: user.id,
        turn_id: turn.turn_id,
        status: "queued",
      }));
      const { error: jobError } = await database
        .from("hope_memory_extraction_jobs")
        .upsert(jobs, { onConflict: "user_id,turn_id", ignoreDuplicates: true });
      if (jobError) {
        return jsonResponse({ error: "History was stored, but its extraction jobs could not be queued." }, 502);
      }
      const { data: jobStatuses, error: statusError } = await database
        .from("hope_memory_extraction_jobs")
        .select("status")
        .eq("user_id", user.id)
        .in("turn_id", turns.map((turn) => turn.turn_id));
      if (statusError) {
        return jsonResponse({ error: "History was stored, but extraction status could not be verified." }, 502);
      }
      const hasWork = (jobStatuses ?? []).some((job: { status: string }) =>
        job.status === "queued" || job.status === "pending" || job.status === "retry_wait"
      );
      if (apiKey && hasWork) spawnJobWorker(user.id);
      return jsonResponse({
        turnsStored: turns.length,
        extractionStatus: hasWork ? "queued_or_pending" : "already_processed",
        excludedSensitiveTurns: payload.turns.length - turns.length,
      }, 202);
    }

    if (operation === "search") {
      if (typeof payload.query !== "string" || payload.query.trim().length === 0 ||
        payload.query.length > 2_000) {
        return jsonResponse({ error: "Memory query must contain 1 to 2,000 characters." }, 400);
      }
      if (apiKey) spawnJobWorker(user.id);
      const [mem0Response, transcriptResult] = await Promise.all([
        apiKey ? fetch(`${MEM0_BASE_URL}/v3/memories/search/`, {
          method: "POST",
          headers: {
            Authorization: `Token ${apiKey}`,
            Accept: "application/json",
            "Content-Type": "application/json",
          },
          signal: AbortSignal.timeout(20_000),
          body: JSON.stringify({
            query: payload.query.trim(),
            filters: { user_id: user.id },
            top_k: 6,
            threshold: 0.25,
          }),
        }).catch((error) => {
          console.error("mem0_retrieval_failed", {
            reason: error instanceof DOMException && error.name === "TimeoutError"
              ? "timeout"
              : "network",
          });
          return null;
        }) : Promise.resolve(null),
        database.rpc("search_hope_conversation_turns", {
          search_query: payload.query.trim(),
          owner_user_id: user.id,
          max_results: 5,
        }),
      ]);
      if (transcriptResult.error) {
        console.error("memory_retrieval_failed", {
          transcriptSearchFailed: Boolean(transcriptResult.error),
        });
        return jsonResponse({ error: "Long-term memory search could not be completed." }, 502);
      }
      let mem0Payload: unknown = null;
      if (mem0Response?.ok) {
        try {
          mem0Payload = await mem0Response.json();
        } catch {
          console.error("mem0_retrieval_invalid_response");
        }
      }
      if (mem0Response && !mem0Response.ok) {
        console.error("mem0_retrieval_failed", { status: mem0Response.status });
      }
      const rawMemories = isObject(mem0Payload) && Array.isArray(mem0Payload.results)
        ? mem0Payload.results
        : [];
      const seenMemories = new Set<string>();
      const memories: MemoryRecord[] = [];
      for (const entry of rawMemories) {
        if (!isObject(entry) || typeof entry.memory !== "string" ||
          entry.user_id !== user.id) continue;
        const normalized = entry.memory.trim().toLocaleLowerCase();
        if (!normalized || seenMemories.has(normalized)) continue;
        seenMemories.add(normalized);
        memories.push({
          id: typeof entry.id === "string" ? entry.id : "",
          memory: entry.memory.trim(),
          metadata: isObject(entry.metadata) ? entry.metadata : null,
          created_at: typeof entry.created_at === "string" ? entry.created_at : null,
          updated_at: typeof entry.updated_at === "string" ? entry.updated_at : null,
          score: typeof entry.score === "number" ? entry.score : undefined,
          user_id: typeof entry.user_id === "string" ? entry.user_id : undefined,
        });
      }
      const conversations = (transcriptResult.data ?? []).map((turn: {
        user_text: string;
        assistant_text: string;
        created_at: string;
      }) => ({
        userText: turn.user_text,
        assistantText: turn.assistant_text,
        createdAt: turn.created_at,
      }));
      const { error: diagnosticError } = await database
        .from("hope_memory_user_diagnostics")
        .upsert(
          {
            user_id: user.id,
            last_retrieval_at: new Date().toISOString(),
            last_mem0_result_count: memories.length,
            last_transcript_result_count: conversations.length,
            updated_at: new Date().toISOString(),
          },
          { onConflict: "user_id" },
        );
      if (diagnosticError) console.error("memory_diagnostic_update_failed");
      return jsonResponse({
        memories,
        conversations,
        retrievedAt: new Date().toISOString(),
        mem0Status: !apiKey ? "unconfigured" : mem0Response?.ok ? "available" : "unavailable",
      });
    }

    if (operation === "memory_search") {
      if (!apiKey) return jsonResponse({ error: "Mem0 is not configured." }, 503);
      if (typeof payload.query !== "string" || payload.query.trim().length === 0 ||
        payload.query.length > 2_000) {
        return jsonResponse({ error: "Memory query must contain 1 to 2,000 characters." }, 400);
      }
      const response = await fetch(`${MEM0_BASE_URL}/v3/memories/search/`, {
        method: "POST",
        headers: {
          Authorization: `Token ${apiKey}`,
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        signal: AbortSignal.timeout(20_000),
        body: JSON.stringify({
          query: payload.query.trim(),
          filters: { user_id: user.id },
          top_k: 20,
          threshold: 0.1,
        }),
      });
      if (!response.ok) return jsonResponse({ error: "Cloud memories could not be searched." }, 502);
      const result: unknown = await response.json();
      const memories = isObject(result) && Array.isArray(result.results)
        ? result.results.filter((entry): entry is MemoryRecord =>
            isObject(entry) && typeof entry.id === "string" &&
            typeof entry.memory === "string" &&
            entry.user_id === user.id
          )
        : [];
      return jsonResponse({ memories });
    }

    if (operation === "update_memory" || operation === "delete_memory") {
      if (!apiKey) return jsonResponse({ error: "Mem0 is not configured." }, 503);
      if (typeof payload.memoryId !== "string" || !MEMORY_ID_PATTERN.test(payload.memoryId)) {
        return jsonResponse({ error: "A valid memory ID is required." }, 400);
      }
      if (operation === "update_memory" &&
        (typeof payload.memory !== "string" || payload.memory.trim().length === 0 ||
          payload.memory.length > 2_000)) {
        return jsonResponse({ error: "Updated memory must contain 1 to 2,000 characters." }, 400);
      }

      const memoryUrl = `${MEM0_BASE_URL}/v1/memories/${encodeURIComponent(payload.memoryId)}/`;
      const existingResponse = await fetch(memoryUrl, {
        headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      });
      if (existingResponse.status === 404) return jsonResponse({ error: "Cloud memory was not found." }, 404);
      if (!existingResponse.ok) return jsonResponse({ error: "Cloud memory ownership could not be verified." }, 502);
      const existing: unknown = await existingResponse.json();
      if (!isObject(existing) || existing.user_id !== user.id) {
        return jsonResponse({ error: "Cloud memory was not found for this account." }, 404);
      }

      if (operation === "update_memory") {
        const updateResponse = await fetch(memoryUrl, {
          method: "PUT",
          headers: {
            Authorization: `Token ${apiKey}`,
            Accept: "application/json",
            "Content-Type": "application/json",
          },
          signal: AbortSignal.timeout(15_000),
          body: JSON.stringify({ text: (payload.memory as string).trim() }),
        });
        if (!updateResponse.ok) return jsonResponse({ error: "Cloud memory could not be updated." }, 502);
        const updated: unknown = await updateResponse.json();
        if (!isObject(updated) || updated.user_id !== user.id ||
          updated.memory !== (payload.memory as string).trim()) {
          return jsonResponse({ error: "Cloud memory update could not be verified." }, 502);
        }
        return jsonResponse({ updated: true, memory: updated });
      }

      const deleteResponse = await fetch(memoryUrl, {
        method: "DELETE",
        headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      });
      if (!deleteResponse.ok) return jsonResponse({ error: "Cloud memory could not be deleted." }, 502);
      const afterDeleteResponse = await fetch(memoryUrl, {
        headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      });
      if (afterDeleteResponse.status !== 404) {
        return jsonResponse({ error: "Cloud memory deletion could not be verified." }, 502);
      }
      return jsonResponse({ deleted: true });
    }

    if (operation === "status") {
      if (payload.cloudMemoryEnabled === true) {
        const { error: resumeError } = await database
          .from("hope_memory_extraction_jobs")
          .update({
            status: "queued",
            next_attempt_at: new Date().toISOString(),
            lease_expires_at: null,
            updated_at: new Date().toISOString(),
          })
          .eq("user_id", user.id)
          .eq("status", "cancelled");
        if (resumeError) {
          return jsonResponse({ error: "Paused memory jobs could not be resumed." }, 502);
        }
        if (apiKey) spawnJobWorker(user.id);
      }
      const countForStatus = (status: string) =>
        database.from("hope_memory_extraction_jobs")
          .select("turn_id", { count: "exact", head: true })
          .eq("user_id", user.id)
          .eq("status", status);
      const [
        queuedCount,
        processingCount,
        pendingCount,
        retryCount,
        failedCount,
        succeededCount,
        latestAttempt,
        latestSuccess,
        diagnosticResult,
      ] = await Promise.all([
        countForStatus("queued"),
        countForStatus("processing"),
        countForStatus("pending"),
        countForStatus("retry_wait"),
        countForStatus("failed"),
        countForStatus("succeeded"),
        database.from("hope_memory_extraction_jobs")
          .select("last_attempt_at,last_error_category")
          .eq("user_id", user.id)
          .not("last_attempt_at", "is", null)
          .order("last_attempt_at", { ascending: false })
          .limit(1)
          .maybeSingle(),
        database.from("hope_memory_extraction_jobs")
          .select("processed_at")
          .eq("user_id", user.id)
          .eq("status", "succeeded")
          .order("processed_at", { ascending: false })
          .limit(1)
          .maybeSingle(),
        database
          .from("hope_memory_user_diagnostics")
          .select("last_retrieval_at,last_mem0_result_count,last_transcript_result_count,last_delete_status,last_delete_event_id")
          .eq("user_id", user.id)
          .maybeSingle(),
      ]);
      if ([queuedCount, processingCount, pendingCount, retryCount, failedCount, succeededCount]
        .some((result) => result.error) ||
        latestAttempt.error || latestSuccess.error || diagnosticResult.error) {
        return jsonResponse({ error: "Cloud memory diagnostics could not be loaded." }, 502);
      }
      const diagnostic = diagnosticResult.data;
      let mem0KeyStatus = apiKey ? "unverified" : "missing";
      if (apiKey) {
        try {
          const validation = await fetch(`${MEM0_BASE_URL}/v3/memories/search/`, {
            method: "POST",
            headers: {
              Authorization: `Token ${apiKey}`,
              Accept: "application/json",
              "Content-Type": "application/json",
            },
            signal: AbortSignal.timeout(10_000),
            body: JSON.stringify({
              query: "HOPE memory connection check",
              filters: { user_id: user.id },
              top_k: 1,
              threshold: 1,
            }),
          });
          mem0KeyStatus = validation.ok
            ? "valid"
            : validation.status === 401 || validation.status === 403
            ? "invalid"
            : "unavailable";
        } catch {
          mem0KeyStatus = "unavailable";
        }
      }
      let deleteStatus = diagnostic?.last_delete_status ?? "none";
      if (deleteStatus === "pending" && diagnostic?.last_delete_event_id && apiKey) {
        try {
          const eventResponse = await fetch(
            `${MEM0_BASE_URL}/v1/event/${encodeURIComponent(diagnostic.last_delete_event_id)}/`,
            {
              headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
              signal: AbortSignal.timeout(10_000),
            },
          );
          if (eventResponse.ok) {
            const event: unknown = await eventResponse.json();
            if (isObject(event) && typeof event.status === "string") {
              const eventState = event.status.toUpperCase();
              if (eventState === "SUCCEEDED" || eventState === "FAILED") {
                deleteStatus = eventState.toLowerCase();
                await database
                  .from("hope_memory_user_diagnostics")
                  .update({ last_delete_status: deleteStatus, updated_at: new Date().toISOString() })
                  .eq("user_id", user.id);
              }
            }
          }
        } catch {
          console.error("memory_delete_status_poll_failed");
        }
      }
      const counts = {
        queued: queuedCount.count ?? 0,
        processing: processingCount.count ?? 0,
        pending: pendingCount.count ?? 0,
        retryWait: retryCount.count ?? 0,
        failed: failedCount.count ?? 0,
        succeeded: succeededCount.count ?? 0,
      };
      return jsonResponse({
        authenticated: true,
        mem0Configured: Boolean(apiKey),
        mem0KeyStatus,
        extractionCounts: counts,
        lastExtractionAttemptAt: latestAttempt.data?.last_attempt_at ?? null,
        lastSuccessfulExtractionAt: latestSuccess.data?.processed_at ?? null,
        lastErrorCategory: latestAttempt.data?.last_error_category ?? null,
        lastRetrievalAt: diagnostic?.last_retrieval_at ?? null,
        lastMem0ResultCount: diagnostic?.last_mem0_result_count ?? 0,
        lastTranscriptResultCount: diagnostic?.last_transcript_result_count ?? 0,
        lastDeleteStatus: deleteStatus,
      });
    }

    if (operation === "pause") {
      const { error } = await database
        .from("hope_memory_extraction_jobs")
        .update({
          status: "cancelled",
          lease_expires_at: null,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", user.id)
        .in("status", ["queued", "processing", "pending", "retry_wait"]);
      if (error) return jsonResponse({ error: "Pending memory jobs could not be paused." }, 502);
      return jsonResponse({ paused: true });
    }

    if (operation === "retry_failed") {
      if (!apiKey) return jsonResponse({ error: "Mem0 is not configured." }, 503);
      const { data, error } = await database
        .from("hope_memory_extraction_jobs")
        .update({
          status: "queued",
          attempts: 0,
          mem0_event_id: null,
          last_error_category: null,
          next_attempt_at: new Date().toISOString(),
          lease_expires_at: null,
          processed_at: null,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", user.id)
        .eq("status", "failed")
        .select("turn_id");
      if (error) return jsonResponse({ error: "Failed memory jobs could not be restarted." }, 502);
      const queued = data?.length ?? 0;
      if (queued > 0) spawnJobWorker(user.id);
      return jsonResponse({ queued });
    }

    if (operation === "clear") {
      if (!apiKey) return jsonResponse({ error: "Long-term memory service is not configured." }, 503);
      const deleteUrl = new URL(`${MEM0_BASE_URL}/v1/memories/`);
      deleteUrl.searchParams.set("user_id", user.id);
      const [mem0Response, transcriptResult] = await Promise.all([
        fetch(deleteUrl, {
          method: "DELETE",
          headers: { Authorization: `Token ${apiKey}`, Accept: "application/json" },
          signal: AbortSignal.timeout(20_000),
        }),
        database.from("hope_conversation_turns").delete().eq("user_id", user.id),
      ]);
      if (!mem0Response.ok || transcriptResult.error) {
        console.error("memory_clear_failed", {
          mem0Status: mem0Response.status,
          transcriptDeleteFailed: Boolean(transcriptResult.error),
        });
        return jsonResponse({
          error: "Some cloud memories could not be cleared. Check status and retry.",
          transcriptsDeleted: !transcriptResult.error,
          mem0DeletionQueued: mem0Response.ok,
        }, 502);
      }
      const deletion: unknown = await mem0Response.json().catch(() => null);
      const eventId = isObject(deletion) && typeof deletion.event_id === "string"
        ? deletion.event_id
        : null;
      if (!eventId || !MEMORY_ID_PATTERN.test(eventId)) {
        return jsonResponse({
          error: "Transcripts were deleted, but Mem0 did not return a verifiable deletion event.",
          transcriptsDeleted: true,
          mem0DeletionStatus: "failed",
        }, 502);
      }
      const deleteStatus = "pending";
      const { error: diagnosticsError } = await database
        .from("hope_memory_user_diagnostics")
        .upsert(
          {
            user_id: user.id,
            last_delete_event_id: eventId,
            last_delete_status: deleteStatus,
            last_mem0_result_count: 0,
            last_transcript_result_count: 0,
            updated_at: new Date().toISOString(),
          },
          { onConflict: "user_id" },
        );
      if (diagnosticsError) {
        console.error("memory_delete_status_record_failed");
        return jsonResponse({
          error: "Mem0 deletion was queued, but its verification status could not be stored.",
          transcriptsDeleted: true,
          mem0DeletionStatus: "pending",
        }, 502);
      }
      return jsonResponse({
        transcriptsDeleted: true,
        mem0DeletionStatus: deleteStatus,
        deletionEventId: eventId,
      }, 202);
    }

    return jsonResponse({ error: "Unsupported memory operation." }, 400);
  } catch {
    console.error("memory_request_failed");
    return jsonResponse({ error: "The memory request could not be completed." }, 500);
  }
});
