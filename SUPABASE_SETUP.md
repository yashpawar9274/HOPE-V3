# HOPE cloud long-term memory setup

HOPE uses Supabase as its private transcript database and Edge Function gateway, and Mem0 as its semantic memory extraction/search service. Users do **not** create a Supabase account. The app reuses the existing Firebase sign-in; the verified Firebase UID is the only memory owner ID.

Cloud memory is **off by default**. When the user enables it in **Settings → Neural Memory Bank**, HOPE asks for consent, backs up locally retained chat turns already associated with that Firebase account once, and saves later completed text chats and regular voice transcripts. Older untagged history is not copied, to avoid mixing data from different sign-ins. Clipboard requests and automation-only turns are excluded. Audio is never stored. Users can pause cloud memory, import an authorized JSON history archive, export their stored memory data, or clear cloud history and request Mem0 deletion from that screen.

## 1. Configure Android's public Supabase endpoint

The project URL and publishable key are public client configuration, not secrets. Set them in the Windows user-level Gradle properties file, outside the repository:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
```

Or provide the same names as Gradle environment variables. Rebuild the app after configuration. Never put a Supabase secret/service-role key, `MEM0_API_KEY`, or Gemini server key in Android or Gradle properties.

## 2. Authenticate requests with the existing Firebase account

The Android app sends its Firebase ID token to `hope-memory`. The function verifies the signature using Google's published signing certificates and checks the configured Firebase project ID, issuer, audience, expiry, auth time, and subject. Its `verify_jwt` gateway check is disabled because Firebase tokens are not Supabase-issued JWTs; no operation runs before this explicit verification succeeds.

After verification, the function uses Supabase's server-provided `SUPABASE_SERVICE_ROLE_KEY` to access the database. Every stored or searched owner ID comes only from the verified Firebase token; the request body cannot choose a `user_id`. The service-role key stays inside the Edge Function and must never be added to Android, Gradle properties, or client responses. This server-side pattern avoids requiring a Firebase custom `role: authenticated` claim or a Supabase Third-Party Auth dashboard integration.

## 3. Apply the database migration

Install the Supabase CLI, authenticate, and link the existing project:

```powershell
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

`supabase/migrations/202611110001_hope_long_term_memory.sql` creates the initial transcript table with RLS enabled and forced. `202611120001_hope_memory_service_search.sql` scopes search to a server-supplied owner ID; `202611120002_hope_memory_extraction_jobs.sql` adds bounded Mem0 retries and leases. `202611140001_hope_memory_source_metadata.sql` adds searchable source timestamps and conversation references. `202611140002_hope_memory_knowledge_graph.sql` adds per-message history, versioned facts, projects, entities, relationships, conversation summaries, import/export records, audit events, pagination/search indexes, and backend functions for the Memory & Knowledge dashboard. Its user-scoped API is still mediated by the Firebase-verifying Edge Function; Android never accesses these tables directly and never receives the service-role key. Migrations `202611120003` and `202611120004` cover the separate Cloud Agent gateway and plan publishing.

Apply all reviewed migrations before deploying the matching Edge Function. The advanced dashboard, history import/export, fact correction/deletion, and index-health endpoints require `202611140001` and `202611140002`; the Android app cannot apply database migrations.

Test with two Firebase accounts: each should save, search, and clear only their own rows. An unauthenticated request must be rejected, and a request body containing a forged owner ID must never affect another user's rows.

## 4. Set server-side Firebase and Mem0 configuration

Use the Firebase project ID from the app's `google-services.json` and a Mem0 API key created in Mem0. Set them only as Supabase function secrets:

```powershell
supabase secrets set FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID MEM0_API_KEY=YOUR_MEM0_API_KEY GEMINI_API_KEY=YOUR_GEMINI_SERVER_KEY
supabase functions deploy hope-memory
```

`MEM0_API_KEY` is used only by the Edge Function to call Mem0's `/v3/memories/add/`, `/v3/memories/search/`, and user-scoped deletion endpoint. `GEMINI_API_KEY` is used server-side for structured memory consolidation; if it is absent, source history remains stored/searchable, but structured facts, project records, and summaries are not extracted. The verified Firebase UID is passed as Mem0 `user_id`; clients cannot supply or override it. `SUPABASE_SERVICE_ROLE_KEY` is provided to the Edge Function runtime by Supabase and is used only for server-side database access.

After applying migrations and setting secrets, redeploy the function so it can store and search source history, queue Mem0 extraction and Gemini consolidation, and report job status. A stored transcript is distinct from a successful extraction: Mem0 extraction is marked successful only after Mem0 reports its asynchronous event as `SUCCEEDED`; structured consolidation is tracked by its own per-turn jobs. Queued work is resumed by a later authenticated app request/status refresh; there is no scheduled worker in this setup.

### Authorized history archive format

The in-app importer accepts `hope-memory-export-v1` archives created by HOPE or a normalized JSON file using this shape:

```json
{
  "format": "hope-history-v1",
  "turns": [
    {
      "turnId": "optional UUID",
      "userText": "original user message",
      "assistantText": "original assistant message",
      "sourceCreatedAt": "2025-03-04T05:06:07Z",
      "sourceConversationId": "original conversation reference",
      "sourceTurnId": "original turn reference",
      "sourceUserMessageId": "original user message reference",
      "sourceAssistantMessageId": "original assistant message reference",
      "sourceTurnOrder": 0,
      "sourceConversationTitle": "optional title"
    }
  ]
}
```

Imports are owner-scoped, split into batches of ten turns, resumable, and deduplicated by the archive SHA-256. Missing IDs are assigned deterministic internal turn IDs; missing source timestamps are marked as server-ingested, while supplied timestamps without trustworthy provenance are marked `imported_unverified`. Credential-like content is excluded from indexing. A third-party export must be converted to the normalized format first; HOPE does not fetch private history from other services or claim that unavailable history was imported.

The export is paginated and writes a SHA-256 checksum plus record counts to the user's export history. This checksum is not a verified server backup: automated Supabase backup configuration remains an operator/platform responsibility, and importing an archive reprocesses its source turns rather than restoring every derived fact, graph edge, or summary byte-for-byte. Keep the downloaded export in an appropriately protected location.

The app's existing Gradle config already accepts `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. Never share the Mem0 key or put it in `local.properties`, app source, `BuildConfig`, or the APK. Supabase provides the service-role key to the Edge Function runtime; do not copy it into Android or expose it to clients.

## 6. Cloud Agent gateway and runtime (Phases 1–3)

The Cloud Agent gateway is a separate, opt-in feature. It reuses Firebase token verification and the server-side service-role database client; it does not reuse Cloud Memory consent. In the app, enable **Cloud Agent Tasks** from **Settings → Neural Memory Bank** only after reading its consent prompt. Cloud task objectives/status are stored under the verified Firebase UID. Enabling this switch does not grant Android permissions, authorize device actions, or enable memory retrieval.

The control plane provides:

- Create an agent session/task with a per-user idempotency key.
- Fetch task state and ordered events with a bounded sequence cursor for reconnect/polling.
- Pause, resume, or cancel a task.
- Read pending tool-call requests and submit one idempotent result tied to the verified user's task.
- Enforce at most 5 active tasks and 20 newly created tasks per rolling 24-hour period per user, plus 120 gateway requests per minute.
- Revoke pending tool-call requests and cancel active tasks when consent is disabled.

Phase 2 adds `cloud-agent-runtime`, a standalone Kotlin/JVM 21 service using Google ADK Kotlin. It claims consented tasks, sends objectives to Gemini, validates plans, and atomically publishes proposals. Coding-intent tasks use a separate stateless Gemini Interactions provider, with a configurable model/fallback and a bounded timeout; its structured file proposals are never written or executed by the worker. Each claim has a lease and execution-attempt check to prevent stale publication. ADK session state is in-memory only and does not retrieve Supabase/Mem0 memories. Phase 3 adds the Android task screen and local tool bridge. It supports device-context reads and lowering brightness only; each device proposal requires explicit confirmation, Android permission checks, and read-back verification. Device readings are sent to the cloud only after approval. No message sending, arbitrary accessibility commands, background polling, memory retrieval, or cloud-side device execution is included.

Build and test the worker locally:

```powershell
.\gradlew.bat -p cloud-agent-runtime test
.\gradlew.bat -p cloud-agent-runtime installDist
```

For a future Cloud Run deployment, configure `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, and `GEMINI_API_KEY` as Secret Manager-backed environment variables. Use always-allocated CPU, at least one instance for its polling loop, and authenticated invocation only. Do not copy secrets into the Docker image, source, Android build, or APK. Cloud Agent consent explicitly discloses that objectives go to Gemini.

Optional coding-provider settings are `GEMINI_CODING_MODEL` (defaults to `gemini-4-carbon`), `GEMINI_CODING_FALLBACK_MODEL` (unset unless explicitly chosen; tried only after HTTP 404), and `GEMINI_CODING_TIMEOUT_SECONDS` (15–180, default 90). These affect the worker only and are not Android client settings.

Once the migration has been reviewed and approved for the intended project, apply it and deploy only the new function:

```powershell
supabase db push --remote
supabase functions deploy hope-agent-gateway --remote=YOUR_PROJECT_REF --use-api --no-verify-jwt
```

`verify_jwt` is disabled at the Supabase gateway because the app sends Firebase tokens, not Supabase-issued JWTs. The function itself verifies each Firebase token before reading or mutating user data. Never expose the Supabase service-role key to the app or Cloud Run's public clients. The Kotlin Android API is `CloudAgentRepository`; it keeps Cloud Agent off by default and checks both local opt-in and account ownership before non-consent calls.

Migrations `202611120003` and `202611120004`, `hope-agent-gateway`, and the Cloud Run worker are source-only until explicitly applied/deployed. Verify authenticated consent, task create/replay, event pagination, cancellation, quota behavior, plan publication, tool-result replay and cross-account isolation against a non-production project before production use.

## 5. Deploy the optional Gemini gateway

Existing Gemini and Gemini Live requests remain on their current client-side paths. To configure the optional text gateway scaffold:

```powershell
supabase secrets set GEMINI_API_KEY=YOUR_GEMINI_SERVER_KEY
supabase functions deploy hope-ai-gateway
```

This key belongs only in Supabase Function Secrets. The gateway is not wired into HOPE's existing AI path, so this setup does not migrate Gemini Live, image requests, or normal Gemini traffic.

## Behavior and limitations

- Mem0 extraction is asynchronous. A successful save means the transcript is stored and extraction was queued; newly extracted facts may not be searchable immediately.
- Search combines Mem0 memories, matching original messages, and structured facts. Oldest/newest ordering and conversation/date/project/type filters are supported by the backend; semantic recall is not guaranteed to find every conversation verbatim.
- Existing local chat history is capped by the app at 100 turns; only turns tagged to the currently signed-in Firebase account can be backed up when cloud memory is first enabled. Older untagged history and earlier deleted local turns cannot be recovered.
- Clearing memory removes source messages, transcripts, structured facts, projects, graph data, summaries, and import/export metadata, and requests user-scoped Mem0 deletion; Mem0 processes deletion asynchronously. Minimal deletion audit events are retained.
- The new 20261114 migrations and advanced-memory endpoints are source changes until applied/deployed and have not been tested against the production Supabase project in this work session.
- Production project `hopeapplication` has migrations `202611110001`, `202611120001`, and `202611120002` applied, and `hope-memory` v4 deployed. An unauthenticated request was verified to return HTTP 401. An authenticated app test is still needed to verify transcript save, Mem0 extraction, retrieval/context injection, and clear-all status.
- Existing Gemini credentials remain client-held and can be recovered from a compromised or instrumented device. The optional gateway does not silently replace the existing paths.
