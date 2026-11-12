# Changelog

Notable changes to HOPE V3 are documented here. Implementation and release availability are not the same: Cloud Agent backend services described for 2.0.0 are source-only until deployed.

## 2.0.0 — Cloud Agent Phases 1–3

### Added

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
