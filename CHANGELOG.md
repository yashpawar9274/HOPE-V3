# Changelog

Notable changes to HOPE V3 are documented here. Implementation and release availability are not the same: Cloud Agent backend services described for 2.0.0 are source-only until deployed.

## 3.1.5

- Remove on-device Gemma inference, its model manager/settings screen, and the LiteRT-LM dependency.
- Remove Offline/Automatic AI routing; migrate old routing preferences to Gemini Online.
- Fix Live voice being blocked with `OFFLINE VOICE UNAVAILABLE` when the old local-only mode was selected.
- Retain the separate local Vosk wake-word detector; it does not provide offline conversational AI.
- Leave already-downloaded Gemma model files in app storage; they are no longer loaded by HOPE.
- Bump the Android app to `3.1.5` (version code `170`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; all 191 unit tests passed.
- The final debug APK is about 110.5 MB. No Android device or `adb` was available for Live voice verification.

## 3.1.4

- Redesign Settings as a searchable, category-filtered control hub while preserving existing preference controls and navigation.
- Add All, AI & Voice, Device, Automation, and Security filters with live result counts and a no-results state.
- Refresh the Settings header, search field, and category controls to use the active HOPE theme.
- Bump the Android app to `3.1.4` (version code `169`).

## 3.1.3

- Redesign the in-app Updates screen as a compact feed with All, Features, Fixes, and Pending filters.
- Keep long release notes collapsed by default with an accessible control to expand the full details.
- Add a dedicated pending-work update so incomplete features and deployment/device-validation requirements remain visible.
- Bump the Android app to `3.1.3` (version code `168`).

### Validation

- `:app:lintDebug` and `:app:assembleDebug` passed; lint reported 0 errors and 1,044 warnings.
- The unit-test suite was not rerun for this UI-only change; 196 tests passed in the preceding 3.1.2 validation.
- The debug APK was rebuilt at `app/build/outputs/apk/debug/app-debug.apk` (about 159 MB).
- No Android device or `adb` was available for an on-device preview.

## 3.1.2

- Route clipboard text through the shared AI router so Local Text mode uses the on-device Gemma model instead of bypassing the selected provider.
- Prevent Gemini Live voice, clipboard image analysis, and new/resumed Cloud Agent AI work when the selected mode does not permit cloud inference.
- Clarify the current local model and feature boundaries in Settings and the in-app Updates screen.
- Record the incomplete llama.cpp/GGUF, Ollama, offline voice/image, Cloud Memory, active-task, and device-validation work instead of claiming full offline readiness.
- Bump the Android app to `3.1.2` (version code `167`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; all 196 unit tests passed.
- Lint completed with 0 errors and 1,044 warnings; warnings remain elsewhere in the project.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk` (about 159 MB).
- No Android device was available (`adb` is not installed), so installation, voice behavior, and device-level offline isolation were not verified.

## 3.1.1

- Expand the in-app Updates screen with detailed, user-facing descriptions of the implemented persona, Core Behaviour, memory, social auto-reply, login/licence, call, media, clipboard, and Cloud Agent features, including deployment and device-test limitations.
- Filter individually sensitive messages out of AI conversation context, block a sensitive latest message from AI requests, and fall back to the latest message when combined history still matches a sensitive pattern.
- Add regression coverage for sensitive content across conversation history.
- Bump the Android app to `3.1.1` (version code `166`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; all 195 unit tests passed.
- Lint completed with 0 errors and 1,044 warnings; warnings remain elsewhere in the project.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk`.
- No Android device was connected; device behavior and message delivery were not verified.

## 3.1.0

- Add an opt-in, WhatsApp-only ULTRON Auto-Defense path using high-confidence contextual AI classification, per-conversation hashed session state, configurable sensitivity, and an automatic timeout.
- Preserve the globally selected persona during takeover; require ULTRON to identify itself in its first submitted response and restore the original persona after normal conversation, timeout, or manual changes.
- Add Smart Auto-Reply settings and WhatsApp-scoped text/voice commands for persona selection and defense controls. Voice-starting WhatsApp replies no longer enables Instagram or SMS as a side effect.
- Keep abusive personal attacks, threats, and slurs out of defense replies. Classification failures and ambiguous/quoted/playful content fail closed.
- Keep sensitive messages out of AI context: the latest sensitive message is not sent, sensitive history is filtered, and ambiguous cross-message patterns fall back to the latest message only.
- Keep defense session metadata local; no Supabase schema or migration was added.
- Bump the Android app to `3.1.0` (version code `165`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; all 194 unit tests passed.
- Lint completed with 0 errors and 1,044 warnings; warnings remain elsewhere in the project.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk`.
- No Android device was connected; physical-device classification and WhatsApp inline-reply delivery remain unverified. Android confirms action submission, not recipient delivery.

## 3.0.9

- Sharpen ULTRON's original voice with optional, pointed playful roasts about low-stakes choices or reasoning; teasing stops when unwanted and is excluded from serious or sensitive situations.
- Update the in-app Updates entry with the refined ULTRON behavior.
- Bumped the Android app to `3.0.9` (version code `164`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; 187 unit tests passed.
- Lint reported 1,074 warnings and no errors.
- Device-level tone and voice behavior was not verified.

## 3.0.8

- Refine the four existing HOPE, FRIDAY, E.D.I.T.H., and ULTRON behavior profiles while keeping HOPE as the default and preserving the shared safety and permission rules.
- Remember a supported Gemini voice separately for each persona; unique defaults are provided. Voice preview and independent pitch/rate controls are not available in the current Gemini Live integration.
- Keep ULTRON's dry, occasional sarcasm focused on ideas and decisions, not personal traits or insults.
- Add the persona and voice improvements to the in-app Updates screen.
- Bumped the Android app to `3.0.8` (version code `163`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; 187 unit tests passed.
- Lint reported 1,074 warnings and no errors.
- Persona selection was validated with unit tests and an APK build; device-level audio behavior was not verified.

## 3.0.7

- Give HOPE, FRIDAY, E.D.I.T.H., and ULTRON distinct names and original behavioral profiles; HOPE remains the default.
- Apply selected persona identity consistently to text and voice prompts, Smart Auto Reply, manual reply previews, clipboard analysis, Meeting Mode SMS, and voice expertise guidance.
- Answer explicit identity questions with the selected persona's name without unsolicited introductions or human impersonation.
- Bumped the Android app to `3.0.7` (version code `162`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; 187 unit tests passed.
- Lint reported 1,073 warnings and no errors.
- Device-level persona/voice behavior and external messaging delivery were not verified.

## 3.0.6

- Fix the Android 7+ resource-qualifier theme override that dropped custom `themeBg` and other app theme attributes on API 27+; preserve the full theme while retaining light-navigation-bar styling.
- Bumped the Android app to `3.0.6` (version code `161`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; 183 unit tests passed.
- Lint reported 1,073 warnings and no errors.
- AAPT2 confirmed all six active app themes retain `themeBg` and have no incomplete API-qualified override.
- Installed on an API 37 emulator: MainActivity opened with the saved licence session, opened again after a cold restart, and remained open after Light → Dark → Light theme changes; no matching app-process inflation exception was logged.
- Physical-device testing and fresh licence activation were not available; the requested real-device acceptance check remains outstanding.

## 3.0.5

- Keep the dashboard visible after licence verification instead of automatically opening optional phone-role setup; setup remains available from its existing entry points.
- Reuse a valid, recently verified offline licence token at launch and prevent duplicate gate/key navigation.
- Make Firebase Auth discovery and cached licence persistence failures non-fatal, with diagnostics; a server-approved activation still opens HOPE if local offline-token storage is unavailable.
- Bumped the Android app to `3.0.5` (version code `160`).

### Validation

- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed; 183 unit tests passed.
- Lint reported 1,073 warnings and no errors.
- Confirmed the merged debug manifest includes `FirebaseInitProvider` and the package remains `com.jarvis.assistant`.
- No physical-device licence activation or crash reproduction was available.

## 3.0.4

- Keep the dedicated dark authentication theme on login, licence-gate, and licence-key screens instead of overwriting it with the user's general app theme.
- Bumped the Android app to `3.0.4` (version code `159`).

### Validation

- Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed.
- Authentication was not exercised on a physical device in this environment.

## 3.0.3

- Load only the selected home tab at startup; other tabs are created when first opened to reduce launch-time view and WebView work.
- Keep unexpected licence activation errors on the licence screen with a visible message instead of allowing an uncaught coroutine exception to close the app.
- Bumped the Android app to `3.0.3` (version code `158`).

### Validation

- Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed.
- No physical-device crash reproduction was available.

## 3.0.2

- Bumped the Android app to `3.0.2` (version code `157`).
- Added a separate `HOPE Call` launcher entry with an orb-style adaptive icon. It opens the dialer without opening the main HOPE screen or starting the voice assistant service; HOPE must still be the default phone app to use its full dialer.
- The new launcher entry appears in the app launcher. Android requires the user to add or move it to the Home screen; apps cannot place icons there silently.
- Updated incoming, outgoing, and active call screens to load the same bundled WebGL orb renderer as the main app, with matching call states.

### Validation

- Android `testDebugUnitTest` and `assembleDebug` passed.
- No physical-device launcher or call-screen test was performed.

## 3.0.1

- Bumped the Android app to `3.0.1` (version code `156`).
- Added a share target for links and a themed Media Download screen with paste, progress, source-link, and share controls.
- Added voice commands for downloading a shared video or getting its audio/song. A voice request starts only after the user confirms ownership/permission; an unchecked request waits for that confirmation.
- Downloads are restricted to direct public HTTPS audio/video file responses, validated as playable media, and capped at 250 MB. Streaming-platform pages, DRM-protected content, unsupported formats, private-network hosts, and non-HTTPS URLs are rejected.
- Supports saving direct video/audio files; supported AAC/AMR audio tracks can be extracted from a direct video into M4A. Files are saved in the Android media library on Android 10+ or HOPE's private app storage on older Android versions.
- Sharing always uses Android's Share Sheet; HOPE does not send downloaded files to a recipient automatically.

### Validation

- Android `testDebugUnitTest` and `assembleDebug` passed, including parser and link-policy tests.
- No physical-device test or test against a real third-party media provider was performed.

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
