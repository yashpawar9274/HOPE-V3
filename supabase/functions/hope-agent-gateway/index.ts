import {
  corsHeaders,
  jsonResponse,
  memoryDatabaseClient,
  readJsonObject,
  verifiedUser,
} from "../_shared/auth.ts";
import {
  isAgentUuid,
  isToolResultStatus,
  parseEventPage,
  stableJson,
} from "./policy.ts";

const TERMINAL_STATES = ["completed", "failed", "cancelled"];
const MAX_ACTIVE_TASKS = 5;
const MAX_TASKS_PER_DAY = 20;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function appendEvent(
  database: ReturnType<typeof memoryDatabaseClient>,
  userId: string,
  taskId: string,
  eventType: string,
  payload: Record<string, unknown> = {},
): Promise<boolean> {
  const { error } = await database.rpc("append_hope_agent_event", {
    owner_user_id: userId,
    target_task_id: taskId,
    new_event_type: eventType,
    new_payload: payload,
  });
  if (error) console.error("agent_event_append_failed", { eventType });
  return !error;
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") return jsonResponse({ error: "Method not allowed." }, 405);

  try {
    const user = await verifiedUser(request);
    if (!user) return jsonResponse({ error: "Authentication required." }, 401);
    const payload = await readJsonObject(request, 64_000);
    if (!payload) return jsonResponse({ error: "Invalid or oversized JSON request." }, 400);
    const database = memoryDatabaseClient();
    const { data: withinQuota, error: quotaError } = await database.rpc(
      "consume_hope_agent_gateway_quota",
      { owner_user_id: user.id, max_requests: 120 },
    );
    if (quotaError) return jsonResponse({ error: "Agent request capacity could not be checked." }, 502);
    if (withinQuota !== true) return jsonResponse({ error: "Cloud Agent request rate limit reached." }, 429);

    if (payload.operation === "consent") {
      if (typeof payload.enabled !== "boolean") {
        return jsonResponse({ error: "An explicit enabled value is required." }, 400);
      }
      if (!payload.enabled) {
        const { error } = await database.rpc(
          "revoke_hope_agent_consent",
          { owner_user_id: user.id },
        );
        if (error) return jsonResponse({ error: "Cloud agent consent could not be revoked safely." }, 502);
        return jsonResponse({ enabled: false });
      }
      const { error } = await database.from("hope_agent_consents").upsert(
        { user_id: user.id, enabled: true, updated_at: new Date().toISOString() },
        { onConflict: "user_id" },
      );
      if (error) return jsonResponse({ error: "Cloud agent consent could not be updated." }, 502);
      return jsonResponse({ enabled: true });
    }

    if (payload.operation === "create_task") {
      if (typeof payload.objective !== "string" || payload.objective.trim().length === 0 ||
        payload.objective.length > 4_000 || !isAgentUuid(payload.idempotencyKey)) {
        return jsonResponse({ error: "Objective or idempotency key is invalid." }, 400);
      }
      const objective = payload.objective.trim();
      const { data: consent, error: consentError } = await database
        .from("hope_agent_consents")
        .select("enabled")
        .eq("user_id", user.id)
        .maybeSingle();
      if (consentError) return jsonResponse({ error: "Cloud agent consent could not be checked." }, 502);
      if (!consent?.enabled) return jsonResponse({ error: "Cloud Agent is not enabled for this account." }, 403);

      const { data: duplicate, error: duplicateError } = await database
        .from("hope_agent_tasks")
        .select("task_id,session_id,objective,state,created_at")
        .eq("user_id", user.id)
        .eq("idempotency_key", payload.idempotencyKey)
        .maybeSingle();
      if (duplicateError) return jsonResponse({ error: "Task idempotency could not be checked." }, 502);
      if (duplicate) {
        if (duplicate.objective !== objective) {
          return jsonResponse({ error: "Idempotency key was already used for a different objective." }, 409);
        }
        return jsonResponse({ ...duplicate, replayed: true });
      }

      const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
      const [activeResult, dailyResult] = await Promise.all([
        database.from("hope_agent_tasks")
          .select("task_id", { count: "exact", head: true })
          .eq("user_id", user.id)
          .not("state", "in", "(completed,failed,cancelled)"),
        database.from("hope_agent_tasks")
          .select("task_id", { count: "exact", head: true })
          .eq("user_id", user.id)
          .gte("created_at", since),
      ]);
      if (activeResult.error || dailyResult.error) {
        return jsonResponse({ error: "Task capacity could not be checked." }, 502);
      }
      if ((activeResult.count ?? 0) >= MAX_ACTIVE_TASKS ||
        (dailyResult.count ?? 0) >= MAX_TASKS_PER_DAY) {
        return jsonResponse({ error: "Cloud task rate limit reached. Try again later." }, 429);
      }

      let sessionId: string;
      if (payload.sessionId === undefined || payload.sessionId === null) {
        const { data: session, error } = await database
          .from("hope_agent_sessions")
          .insert({ user_id: user.id })
          .select("session_id")
          .single();
        if (error || !session) return jsonResponse({ error: "Agent session could not be created." }, 502);
        sessionId = session.session_id;
      } else {
        if (!isAgentUuid(payload.sessionId)) return jsonResponse({ error: "Session ID is invalid." }, 400);
        const { data: session, error } = await database
          .from("hope_agent_sessions")
          .select("session_id,status")
          .eq("user_id", user.id)
          .eq("session_id", payload.sessionId)
          .maybeSingle();
        if (error) return jsonResponse({ error: "Agent session could not be checked." }, 502);
        if (!session || session.status !== "active") {
          return jsonResponse({ error: "Agent session is unavailable for this account." }, 404);
        }
        sessionId = session.session_id;
      }

      const { data: task, error: taskError } = await database
        .from("hope_agent_tasks")
        .insert({
          user_id: user.id,
          session_id: sessionId,
          idempotency_key: payload.idempotencyKey,
          objective,
          state: "created",
        })
        .select("task_id,session_id,objective,state,created_at")
        .single();
      if (taskError?.message.includes("agent_task_capacity_exceeded")) {
        return jsonResponse({ error: "Cloud task rate limit reached. Try again later." }, 429);
      }
      if (taskError || !task) {
        const { data: racedTask } = await database
          .from("hope_agent_tasks")
          .select("task_id,session_id,objective,state,created_at")
          .eq("user_id", user.id)
          .eq("idempotency_key", payload.idempotencyKey)
          .maybeSingle();
        if (racedTask?.objective === objective) {
          return jsonResponse({ ...racedTask, replayed: true });
        }
        return jsonResponse({ error: "Cloud task could not be created." }, 502);
      }
      if (!await appendEvent(database, user.id, task.task_id, "task.created", { state: task.state })) {
        return jsonResponse({
          error: "Task was created, but its event could not be recorded. Retry with the same idempotency key.",
          taskId: task.task_id,
        }, 502);
      }
      return jsonResponse(task, 201);
    }

    const taskId = payload.taskId;
    if (!isAgentUuid(taskId)) return jsonResponse({ error: "A valid task ID is required." }, 400);
    const { data: task, error: taskError } = await database
      .from("hope_agent_tasks")
      .select("task_id,session_id,objective,state,plan,required_permissions,risk_category,approval_status,verification_status,cancellation_requested,failure_code,last_event_sequence,created_at,updated_at,completed_at")
      .eq("user_id", user.id)
      .eq("task_id", taskId)
      .maybeSingle();
    if (taskError) return jsonResponse({ error: "Cloud task could not be loaded." }, 502);
    if (!task) return jsonResponse({ error: "Cloud task was not found." }, 404);

    if (payload.operation === "get_task") {
      const eventPage = parseEventPage(payload.afterSequence, payload.limit);
      if (!eventPage) {
        return jsonResponse({ error: "Event cursor or page size is invalid." }, 400);
      }
      const { data: events, error } = await database
        .from("hope_agent_events")
        .select("sequence,event_type,payload,created_at")
        .eq("user_id", user.id)
        .eq("task_id", taskId)
        .gt("sequence", eventPage.afterSequence)
        .order("sequence", { ascending: true })
        .limit(eventPage.limit);
      if (error) return jsonResponse({ error: "Cloud task events could not be loaded." }, 502);
      const { data: toolRequests, error: toolError } = await database
        .from("hope_agent_tool_calls")
        .select("tool_call_id,tool_name,parameters,permission_scope,risk_category,status,expires_at")
        .eq("user_id", user.id)
        .eq("task_id", taskId)
        .in("status", ["requested", "user_confirmation_required"])
        .gt("expires_at", new Date().toISOString())
        .order("created_at", { ascending: true })
        .limit(20);
      if (toolError) return jsonResponse({ error: "Pending device requests could not be loaded." }, 502);
      const taskCanAcceptDeviceActions = !TERMINAL_STATES.includes(task.state) && task.state !== "paused";
      return jsonResponse({
        task,
        events: events ?? [],
        toolRequests: taskCanAcceptDeviceActions ? toolRequests ?? [] : [],
      });
    }

    if (payload.operation === "finish_task") {
      const { data: finished, error: finishError } = await database.rpc(
        "finish_hope_agent_task",
        { owner_user_id: user.id, target_task_id: taskId },
      );
      if (finishError?.message.includes("agent_tool_results_incomplete")) {
        return jsonResponse({ error: "Every proposed device action must have a result first." }, 409);
      }
      if (finishError?.message.includes("agent_task_not_finishable")) {
        return jsonResponse({ error: "Cloud task is not ready to be finalized." }, 409);
      }
      if (finishError) return jsonResponse({ error: "Cloud task could not be finalized." }, 502);
      return jsonResponse(finished);
    }

    if (payload.operation === "cancel_task" || payload.operation === "pause_task" ||
      payload.operation === "resume_task") {
      if (payload.operation === "resume_task") {
        const { data: consent, error } = await database
          .from("hope_agent_consents")
          .select("enabled")
          .eq("user_id", user.id)
          .maybeSingle();
        if (error) return jsonResponse({ error: "Cloud agent consent could not be checked." }, 502);
        if (!consent?.enabled) return jsonResponse({ error: "Cloud Agent is not enabled for this account." }, 403);
      }
      if (TERMINAL_STATES.includes(task.state)) {
        return jsonResponse({ error: "A finished task cannot be changed.", task });
      }
      const nextState = payload.operation === "cancel_task"
        ? "cancelled"
        : payload.operation === "pause_task"
        ? "paused"
        : "ready";
      const update = {
        state: nextState,
        cancellation_requested: payload.operation === "cancel_task",
        lease_expires_at: null,
        updated_at: new Date().toISOString(),
        ...(nextState === "cancelled" ? { completed_at: new Date().toISOString() } : {}),
      };
      const { data: updated, error } = await database
        .from("hope_agent_tasks")
        .update(update)
        .eq("user_id", user.id)
        .eq("task_id", taskId)
        .eq("state", task.state)
        .select("task_id,state,cancellation_requested,updated_at")
        .maybeSingle();
      if (error) return jsonResponse({ error: "Cloud task state could not be updated." }, 502);
      if (!updated) return jsonResponse({ error: "Cloud task changed concurrently. Refresh and retry." }, 409);
      if (payload.operation !== "resume_task") {
        const { error: callsError } = await database.from("hope_agent_tool_calls")
          .update({ status: "cancelled", updated_at: new Date().toISOString() })
          .eq("user_id", user.id)
          .eq("task_id", taskId)
          .in("status", ["requested", "user_confirmation_required"]);
        if (callsError) return jsonResponse({ error: "Pending device requests could not be revoked." }, 502);
      }
      const eventType = payload.operation === "cancel_task" ? "task.cancelled"
        : payload.operation === "pause_task" ? "task.paused" : "task.resumed";
      if (!await appendEvent(database, user.id, taskId, eventType, { state: nextState })) {
        return jsonResponse({ error: "Task changed, but its event could not be recorded." }, 502);
      }
      return jsonResponse(updated);
    }

    if (payload.operation === "submit_tool_result") {
      const { data: consent, error: consentError } = await database
        .from("hope_agent_consents")
        .select("enabled")
        .eq("user_id", user.id)
        .maybeSingle();
      if (consentError) return jsonResponse({ error: "Cloud agent consent could not be checked." }, 502);
      if (!consent?.enabled || TERMINAL_STATES.includes(task.state) || task.state === "paused") {
        return jsonResponse({ error: "Cloud task is not authorized to accept device results." }, 403);
      }
      const callId = payload.toolCallId;
      const resultKey = payload.idempotencyKey;
      const resultStatus = payload.resultStatus;
      const result = payload.result ?? {};
      if (!isAgentUuid(callId) || !isAgentUuid(resultKey) || !isToolResultStatus(resultStatus) ||
        !isObject(result) ||
        JSON.stringify(result).length > 32_000) {
        return jsonResponse({ error: "Tool result is invalid or too large." }, 400);
      }
      const { data: call, error } = await database
        .from("hope_agent_tool_calls")
        .select("tool_call_id,status,expires_at,result_idempotency_key,result")
        .eq("user_id", user.id)
        .eq("task_id", taskId)
        .eq("tool_call_id", callId)
        .maybeSingle();
      if (error) return jsonResponse({ error: "Tool request could not be checked." }, 502);
      if (!call) return jsonResponse({ error: "Tool request was not found for this account." }, 404);
      const storedStatus = resultStatus === "SUCCESS" ? "executed" : resultStatus.toLowerCase();
      if (call.result_idempotency_key) {
        if (call.result_idempotency_key === resultKey && call.status === storedStatus &&
          stableJson(call.result) === stableJson(result)) {
          const { data: existingEvent, error: eventError } = await database
            .from("hope_agent_events")
            .select("sequence")
            .eq("user_id", user.id)
            .eq("task_id", taskId)
            .eq("event_type", "tool.result")
            .contains("payload", { toolCallId: callId })
            .limit(1)
            .maybeSingle();
          if (eventError) return jsonResponse({ error: "Tool result event could not be checked." }, 502);
          if (!existingEvent && !await appendEvent(database, user.id, taskId, "tool.result", {
            toolCallId: callId,
            resultStatus,
          })) {
            return jsonResponse({ error: "Tool result was stored, but its task event could not be recorded." }, 502);
          }
          return jsonResponse({ accepted: true, replayed: true, result: call.result });
        }
        return jsonResponse({ error: "A different result was already recorded for this tool request." }, 409);
      }
      if (!["requested", "user_confirmation_required"].includes(call.status)) {
        return jsonResponse({ error: "Tool request is no longer awaiting a result." }, 409);
      }
      if (Date.parse(call.expires_at) <= Date.now()) {
        await database.from("hope_agent_tool_calls")
          .update({ status: "expired", updated_at: new Date().toISOString() })
          .eq("user_id", user.id)
          .eq("task_id", taskId)
          .eq("tool_call_id", callId)
          .in("status", ["requested", "user_confirmation_required"]);
        return jsonResponse({ error: "Tool request has expired." }, 410);
      }
      const { data: updated, error: updateError } = await database
        .from("hope_agent_tool_calls")
        .update({
          status: storedStatus,
          result_idempotency_key: resultKey,
          result,
          updated_at: new Date().toISOString(),
        })
        .eq("user_id", user.id)
        .eq("task_id", taskId)
        .eq("tool_call_id", callId)
        .in("status", ["requested", "user_confirmation_required"])
        .is("result_idempotency_key", null)
        .select("tool_call_id,status,result")
        .maybeSingle();
      if (updateError) return jsonResponse({ error: "Tool result could not be stored." }, 502);
      if (!updated) return jsonResponse({ error: "Tool request changed concurrently. Refresh and retry." }, 409);
      if (!await appendEvent(database, user.id, taskId, "tool.result", {
        toolCallId: callId,
        resultStatus,
      })) {
        return jsonResponse({ error: "Tool result was stored, but its task event could not be recorded." }, 502);
      }
      return jsonResponse({ accepted: true, result: updated.result });
    }

    return jsonResponse({ error: "Unsupported agent gateway operation." }, 400);
  } catch {
    console.error("agent_gateway_request_failed");
    return jsonResponse({ error: "The agent gateway request could not be completed." }, 500);
  }
});
