# Changelog

Notable changes to HOPE V3 are documented here. Implementation and release availability are not the same: Cloud Agent backend services described for 2.0.0 are source-only until deployed.

## 3.0

- Set the Android app version to `3.0` (version code `155`).
- Smart Auto-Reply sends the exact first-contact introduction `Hey, I'm HOPE 😼` once per saved conversation, uses up to the latest five available messages, and keeps sender labels in the prompt context.
- Added conversation-aware mood and language guidance, configurable personality/tone, and explicit rules against impersonation, repetitive deflections, unsupported claims, and making personal or high-impact commitments.
- Sensitive and high-impact notifications remain unsent for manual review. Contact-specific suggest-only mode remains non-sending; a separate approval/send queue is not available.
- The Auto-Reply screen now provides an overview of retained message records, recent chat labels, submitted reply actions, service status, supported integrations, and latest activity. Clearing local context also clears saved first-contact state.
- Supported replies remain limited to Android-provided notification quick-reply actions for WhatsApp, Instagram Direct, and SMS where available. Telegram and Facebook Messenger are unsupported; action submission does not confirm delivery.
- Conversation context uses existing on-device notification history and its retention setting. Gemini receives context only when the configured AI-reply path is enabled.
- Improved login and signup field contrast and light/dark styling; added password visibility controls, full-name signup, password-strength guidance, and clearer validation while preserving Firebase email/password, Google sign-in, and password-reset flows.
- Replaced Android 11-only MessagingStyle extraction with AndroidX compatibility parsing for the app's Android 8+ supported range.
- Added Android TextToSpeech fallback when a completed Gemini Live turn contains response text but no generated audio, and guarded voice microphone/playback/WebSocket callbacks against stale conversation sessions.
- Updated the new-install default to the currently recommended Gemini 3.8 Live model, with a single fallback attempt to the legacy Gemini 3.1 Flash Live preview. Existing user-selected model preferences are preserved.
- Expanded voice policy to adapt to emotional cues cautiously; added Hindi and Hinglish transcript-merging regression tests.
- Added the advanced Memory & Knowledge dashboard source: source-linked messages, versioned structured facts, project/graph records, indexed retrieval, resumable JSON import, checksum-recorded export, database-backed health, and correction/deletion controls. The new migrations and Edge Function must be deployed before these additions are available; exports are not verified server backups and restore currently reprocesses source turns.
- Added Cloud Agent coding-intent routing to the stateless Gemini coding provider, bounded structured file proposals, path/output validation, configured-only 404 fallback, and proposal-only Android display. Worker deployment is still required; no file execution, build, or test is claimed.

## 2.0.1

- Added opt-in, local contact-specific notification reply profiles with explicit suggest-only and limited-auto-reply modes; the existing global Smart Auto-Reply remains the fallback.
- Added hashed notification identity matching, contact prompt preferences, profile management/search/clear controls, and metadata-only reply events. Contact profile data uses a separate Room database.
- Tightened notification reply action selection to avoid choosing an ambiguous unrelated `RemoteInput` action.
- Hardened Cloud Agent tool calls with task/session/call identity, request expiry, exact authorized parameters, permission scopes, risk categories, bounded execution, structured outcomes, local confirmation, and verification.
- Added Cloud Agent pause/resume/cancel controls and gateway validation for bounded tool plans and verified results.
- Added the Mem0 extraction-job migration (`202611120002_hope_memory_extraction_jobs.sql`) with bounded retries, expiring worker leases, safe concurrent claiming, and per-user retrieval/deletion diagnostics that do not store transcript contents.
- Cloud Agent gateway and worker deployment and authenticated end-to-end testing remain outstanding; these changes are not a claim that the cloud service is deployed.
- The new memory-job migration must also be applied and its worker configured before the extraction reliability improvements are active.

### Validation

- Android `testDebugUnitTest` and `assembleDebug` passed for the 2.0.1 source update.

The Android app version is `2.0.1` (version code `154`).

## 2.0.0 — Cloud Agent Phases 1–3

### Added

- Hardened the Android tool-call protocol with task/session/call identity, request expiry, exact authorized parameters, required permission scope, risk category, per-tool schemas, and execution timeouts.
- Added structured device results and server-side validation so `SUCCESS` is accepted only with the supported verified result shape; declining an action now reports `CANCELLED`.
- Kept the device allowlist intentionally limited to explicit device-context reads and lowering screen brightness. Suggested calendar, communication, accessibility, and other system tools remain unsupported.
- Added an opt-in Cloud Agent task flow built on the existing Firebase identity and Supabase project. Cloud Agent consent is independent from Cloud Memory consent and defaults off.
- Added the `hope-agent-gateway` Supabase Edge Function for authenticated task creation and control, event retrieval, task state changes, tool results, and consent revocation.
- Added migrations `202611120003_hope_agent_gateway.sql` and `202611120004_hope_agent_plan_publishing.sql` for user-scoped task data, ordered events, tool-call requests, quota enforcement, idempotent operations, atomic plan publication, and finalization.
- Added `cloud-agent-runtime`, a standalone Kotlin/JVM 21 Google ADK worker that claims consented tasks, requests a bounded plan from Gemini, validates it, and publishes approved-format proposals.
- Added an Android Cloud Agent Tasks screen with task submission, status/events, and pause, resume, and cancel controls.
- Added a strict local tool bridge. Supported operations are device-context reads and lowering screen brightness; each proposal requires explicit confirmation, platform permission checks, and read-back verification.
- Added Android and gateway policy tests for the bounded local tool set and authorization rules.

### Privacy and safety boundaries

- Task objectives are sent to Gemini only after Cloud Agent consent. Device context is disclosed only after approval of the specific request.
- The planner has no callable device tools. Its output is validated in the worker and again when proposals are published.
- Device actions run locally. The current allowlist does not include sending messages, arbitrary accessibility actions, enabling connectivity services, or closing applications.
- Cloud Agent does not retrieve Mem0 memories. Its ADK session state is in-memory and is not durable across worker restarts.

### Validation

- Android `testDebugUnitTest` and `assembleDebug` passed.
- Cloud Agent worker `test` and `installDist` passed.
- Supabase Edge Function `deno check` and policy tests passed.
- Deployment and authenticated end-to-end device testing were not performed.

### Deployment status

The migrations, `hope-agent-gateway`, and Cloud Run worker remain source-only. Until they are reviewed and deployed with the required server-side configuration, Cloud Agent tasks will not run end-to-end. Review [the deployment guide](./SUPABASE_SETUP.md#6-cloud-agent-gateway-and-runtime-phases-1-3) before enabling production service.

The Android app version is `2.0.0` (version code `153`).

## 1.9.6

- Added local speaker profiles with explicit enrollment consent, device-authenticated profile management, encrypted embeddings, and opt-in personalized addressing.
- Added guarded WhatsApp auto-reply controls, duplicate protection, sensitive-content exclusions, bounded retries, and honest action-submission status.
- Improved Hey HOPE conversation entry and reconnect behavior.

## 1.9.5

- Added HOPE app lock with PIN, pattern, or password; encrypted local verifier storage; optional biometric unlock; and recovery flows.
- Added opt-in voice clipboard actions and on-device Hey HOPE standby with foreground-service disclosure and manual stop controls.
- Deferred loading offline Vosk models until a voice feature is requested.
