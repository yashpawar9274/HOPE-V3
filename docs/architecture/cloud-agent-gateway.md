# HOPE Cloud Agent — Phases 1–3

## Scope

Phase 1 adds a short-request task control plane. Phase 2 adds a Kotlin/JVM ADK planning worker for Cloud Run. Phase 3 adds an Android approval screen and a small local tool bridge. The worker only proposes actions; device operations remain local and require an explicit per-action approval.

```text
Android CloudAgentRepository
  ├─ Firebase ID token + Supabase publishable key
  └─ Explicit Cloud Agent consent (off by default)
             │ HTTPS POST
             ▼
Supabase hope-agent-gateway Edge Function
  ├─ Verifies Firebase JWT and derives owner UID
  ├─ Enforces consent, request quotas, task caps, and idempotency
  └─ Uses server-only service role
             │
             ▼
Supabase agent tables (RLS enabled/forced; service-role only)
  ├─ user-scoped sessions and tasks
  ├─ monotonically sequenced task events
  └─ expiring tool-call requests and idempotent device results
             ▲
             │ server-only service role + atomic claim/publish RPCs
Cloud Run Kotlin/JVM worker
  └─ ADK Gemini planner emits a bounded plan; it has no device tools
             │
             ▼
Android Cloud Agent Tasks screen
  └─ allowlisted tool bridge; confirmation, Android permission, and read-back verification
```

## Data and authorization

- Every row is scoped to the verified Firebase UID. Client-provided owner IDs are not accepted.
- Cloud Agent consent is stored independently from Cloud Memory consent. It is disabled by default.
- Revoking consent atomically disables processing, cancels active tasks, revokes pending device requests, and records task cancellation events.
- Tasks require a UUID idempotency key and are constrained by database-enforced per-user limits: five active tasks and twenty new tasks per rolling day.
- Authenticated gateway calls are additionally limited to 120 per minute per user.
- Session/task/event/tool-call tables have RLS enabled and forced; direct `anon` and `authenticated` table access is revoked. Only the server-side service role is granted table access.
- Event sequence allocation is atomic. The Android client resumes event polling after its last received sequence.
- A tool result is accepted only for an existing, unexpired request belonging to that user's task, and a request accepts one idempotent result. The Android client must still validate tool schemas, permissions, policy, approvals, and execution locally.
- No transcript or Mem0 retrieval is performed by this gateway. Memory use requires its separate opt-in and a later phase's explicit context policy.
- Task objectives are sent to the configured Gemini model only after Cloud Agent consent. Device context is sent only after the user approves that specific tool request.
- The ADK agent receives no callable device tools. Its JSON output is parsed against a server-side allowlist, and the database publication RPC checks that allowlist again inside the same transaction that stores proposals and events.
- The Android bridge exposes only `get_device_context` and `set_screen_brightness`. Each tool has an exact parameter schema, permission scope, risk category, five-second execution deadline, and bounded result schema. Every proposal requires a tap-through confirmation. Brightness changes require Android's `WRITE_SETTINGS` special access, are restricted to lowering the current setting, and are verified by reading the value back. Other actions are rejected.
- Device requests carry task ID, tool-call ID, session ID, expiration, exact authorized parameters, permission scope, and risk category. Android binds the request to the current authenticated task/session and re-fetches it before execution. Result submission repeats the request envelope; the gateway compares it to the stored request.
- Result statuses are `SUCCESS`, `FAILED`, `PERMISSION_REQUIRED`, `USER_CONFIRMATION_REQUIRED`, `UNSUPPORTED`, `TIMEOUT`, and `CANCELLED`. The gateway requires a schema-valid, verified result for `SUCCESS`; it cannot independently attest to a rooted or compromised Android client.
- The Kotlin/JVM worker keeps supported planner tools in `AgentToolRegistry`; it validates exact parameters, risk and permission scope before publishing a proposal. SQL and Android independently enforce their own allowlists.
- Each Cloud Agent gateway request receives a server-generated `x-request-id`. Structured request logs include route, method, status and duration, but exclude authorization headers, objectives and provider response bodies.
- Android does not poll in the background. The task screen refreshes on user action; tool proposals expire after ten minutes.

## Operations

`consent`, `create_task`, `get_task`, `pause_task`, `resume_task`, `cancel_task`, `finish_task`, and `submit_tool_result`. `get_task` returns bounded ordered events and currently pending, unexpired tool requests.

Task states persisted by the schema: `created`, `understanding`, `planning`, `awaiting_approval`, `ready`, `executing`, `verifying`, `waiting_for_device`, `paused`, `completed`, `failed`, and `cancelled`.

## Phase 2 runtime

`cloud-agent-runtime` is a standalone Kotlin/JVM 21 application using Google ADK Kotlin 1.5.0. It claims tasks through the database RPC, asks ADK/Gemini for a bounded plan, validates output, and publishes it through `publish_hope_agent_plan`. Cloud Run receives `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, and `GEMINI_API_KEY` from Secret Manager at runtime. Never put those secrets in the Android app or image.

Coding requests are routed to a separate Gemini Interactions provider. `GEMINI_CODING_MODEL` defaults to `gemini-4-carbon`; `GEMINI_CODING_FALLBACK_MODEL` is optional and is tried only if the primary model returns HTTP 404. `GEMINI_CODING_TIMEOUT_SECONDS` is bounded to 15–180 seconds. This path publishes up to five validated file proposals, not executable tool calls. The Android task screen can display the proposed source, but the worker does not write project files, execute code, or claim that a build or test passed. The existing ADK planner remains responsible for ordinary conversation and allowlisted device-action proposals.

Build and test locally:

```powershell
.\gradlew.bat -p cloud-agent-runtime test
.\gradlew.bat -p cloud-agent-runtime installDist
```

The worker uses a background polling loop. A Cloud Run deployment must use always-allocated CPU, retain at least one instance, and remain IAM-authenticated; do not allow unauthenticated invocation. The health endpoint exposes no task data.

## Phase 3 device bridge

The task screen is available from the Cloud Agent card in Memory settings. It submits a user objective, shows the model response and task state, and manually refreshes ordered events. A proposal is never executed from a model response alone. The bridge checks expiry, exact tool name, parameter shape, permission scope, and risk again before opening a confirmation dialog. A successful result is reported only after device-side read-back verification.

## Deployment and current limitations

The new gateway, migrations `202611120003` and `202611120004`, and Cloud Run worker remain source-only until explicit production approval. Apply migrations 003 and 004 before deploying the gateway/runtime. Firebase verification happens in the gateway because Supabase cannot validate Firebase-issued tokens.

The worker is not deployed or configured with production secrets. Tasks remain `created` until it is deployed. ADK's session service is in-memory and is not durable across Cloud Run restarts; it does not fetch Supabase or Mem0 memories. Only the two documented device tools are supported. The Android bridge reports its local verification result to the gateway; the server cannot independently attest to a rooted or compromised device. No memory retrieval, automatic message sending, arbitrary accessibility actions, Android background polling, or WebSocket streaming is included.

## Conversation-history metadata

Migration `202611140001_hope_memory_source_metadata.sql` stores each imported turn's source timestamp and timestamp provenance, and adds a full-text index for user-scoped history search. Existing rows use their server `created_at` timestamp and are marked `server_ingested`; this must not be interpreted as the original conversation time. New app imports preserve timestamps available in local chat history. The current Android history model does not provide a conversation ID, and the local chat repository retains at most 100 turns, so older history is not available to import.
