<h1 align="center">✨ HOPE AI — Your Voice Assistant</h1>

<p align="center">
  <strong>Talk to HOPE, get help with everyday tasks, manage notifications, and handle calls — all from one Android app.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-3.1.5-4169E1" alt="Version 3.1.5">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Package-com.jarvis.assistant-6A5ACD" alt="Android package">
</p>

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="HOPE AI app icon" width="160">
</p>

<p align="center">
  <a href="./CHANGELOG.md">Changelog</a> ·
  <a href="./docs/architecture/cloud-agent-implementation.md">Cloud Agent build guide</a> ·
  <a href="./docs/architecture/cloud-agent-gateway.md">Cloud Agent architecture</a> ·
  <a href="./SUPABASE_SETUP.md">Supabase setup</a> ·
  <a href="./docs/README.md">All documentation</a> ·
  <a href="https://github.com/yashpawar9274/HOPE-V3/releases">GitHub releases</a>
</p>

## 👋 About HOPE AI

HOPE is an Android voice assistant with conversational voice features, phone and contact tools, notification intelligence, and on-screen task automation. It combines Gemini Live and Android voice capabilities with system integrations that work when the required permissions and services are enabled.

### Local AI status

HOPE uses Gemini Online for AI text and Live voice; an API key and network access are required. The on-device Gemma inference runtime, model UI, and Offline/Automatic AI routing were removed in version 3.1.5. Vosk remains only for local wake-word detection and does not provide offline conversations. Existing Gemma model files are left in app storage but are no longer loaded. The proposed llama.cpp/GGUF and Ollama migration has not been implemented.

## 🚀 Get started

1. 📥 **Install HOPE:** download the latest APK from [GitHub Releases](https://github.com/yashpawar9274/HOPE-V3/releases). Debug builds are signed with Android's debug key, not a production release key.
2. 🔐 **Sign in:** use the Firebase sign-in configured for this app. Cloud features are unavailable until their backend is deployed.
3. 🛡️ **Grant access deliberately:** allow only the Android permissions or system services needed for the features you choose.
4. 🎛️ **Choose features:** enable optional experiences separately in HOPE settings. Cloud Memory, Cloud Agent, clipboard analysis, standby listening, and other integrations are opt-in.

## 🧭 Cloud Agent at a glance

The Cloud Agent is implemented in source across three phases. It is **not production-deployed**: its Supabase migrations and gateway function, and its Cloud Run worker, still need review and deployment before tasks can run end-to-end.

```mermaid
flowchart LR
    A[Android app<br/>explicit consent] -->|Firebase ID token| B[Supabase gateway]
    B --> C[(RLS-protected task data)]
    C --> D[Cloud Run ADK planner]
    D -->|bounded, allowlisted proposal| B
    B --> E[Android approval screen]
    E -->|per-action approval| F[Local tool bridge]
    F -->|read-back verification| E
    E -->|verified result| B
```

See the [implementation process](./docs/architecture/cloud-agent-implementation.md), [security and architecture details](./docs/architecture/cloud-agent-gateway.md), and [deployment instructions](./SUPABASE_SETUP.md#6-cloud-agent-gateway-and-runtime-phases-1-3).

## 🌟 App features

### 🗣️ Multi-speaker recognition — new in 1.9.6
- Add up to 12 consenting speaker profiles with a preferred name, form of address, and language. Enrollment asks the speaker to explicitly consent and record three prompted samples in a quiet place; retraining and deletion require Android device authentication.
- Reuses the bundled on-device Vosk/Kaldi speaker x-vector model already used by HOPE's owner voice feature. Raw enrollment audio is held in memory only and cleared after each embedding is extracted. The 128-dimensional voice embeddings and profile labels are stored in an AES-GCM encrypted private record with a key held by Android Keystore.
- Enable personalized addressing separately, adjust the cosine-distance threshold, and use UNKNOWN for low-confidence or ambiguous matches. Unknown voices are instructed to receive neutral forms of address.
- Speaker recognition is probabilistic, may be affected by noise, language, device, distance, or replay, and is **not** authentication. Never use it to authorize financial, security, or device-unlock actions. Target-device accuracy, false-accept/false-reject behavior, and replay resistance have not been validated.
- When personalization is enabled, only the selected preferred address and language are sent as response context to the configured AI service; the profile embeddings remain local. The existing HOPE character voice and TTS configuration are unchanged. No new Android permission is required beyond existing microphone access.
- **Setup:** open **Settings → Speaker Profiles**, set a secure Android screen lock (required to manage profiles), add each consenting speaker, review the privacy notice, check consent, and record all three prompts in a quiet place. Then enable personalized addressing and adjust the threshold if needed. The Vosk speaker model is bundled; enrollment and embeddings do not need a model download or internet connection. Microphone permission is required to enroll and for live matching.

### 🔐 HOPE Security Manager — new in 1.9.5
- Protect HOPE's own screens with a separate PIN, 3×3 pattern, or alphanumeric password; credential changes require the existing app credential.
- Store a salted PBKDF2-HMAC-SHA256 verifier (600,000 iterations) inside an AES-GCM-encrypted local record whose key is held by Android Keystore. Plaintext credentials are never persisted or logged.
- Configure automatic relocking immediately or after 1, 5, or 15 minutes in the background. Cold process starts require authentication again. Locked app windows use Android's secure-window flag to prevent screenshots and recents previews.
- Optionally enable strong biometric unlock through Android BiometricPrompt. Recovery asks Android to authenticate the device owner, then resets only HOPE's app lock; HOPE never sees or stores the phone's PIN, pattern, or password.
- Say “Hey HOPE, unlock my phone” to request Android's supported keyguard dismissal flow. Android controls whether its system screen appears and whether authentication succeeds. HOPE requires a visible app Activity for this voice action; otherwise unlock the phone manually and reopen HOPE.
- The app lock does not replace, modify, or bypass Android's device lock. Emergency incoming-call UI remains available.

### 🎙️ Voice clipboard and Hey HOPE standby — new in 1.9.4
- Opt in separately to voice clipboard actions and on-device Hey HOPE standby from Hands-Free settings; both are off by default.
- Say “HOPE, copy the selected text”, “HOPE, copy this link”, “HOPE, paste here”, or “HOPE, select all”. Clipboard actions operate on supported focused or selected content through Android Accessibility and report when the action cannot be verified.
- Explicit HTTP(S) links are validated and kept intact. Password fields and content recognized as passwords, one-time codes, or payment details are blocked; sensitive-content checks are best-effort, not a guarantee.
- No clipboard history is stored. Explicit dictated-text copying uses Android ClipboardManager directly. Selection, link extraction, paste, and select-all require HOPE Accessibility to be enabled separately in Android Settings.
- “HOPE, activate standby mode” or “HOPE, go to standby” enters standby only after HOPE finishes its current response. “Hey HOPE” is checked locally by the bundled Vosk recognizer; ambient audio is not sent to Gemini or retained by this wake detector.
- Standby requires microphone and notification permission, a visible-app start, and an ongoing Android foreground-service notification. Background start, battery, microphone privacy, and screen-lock behavior can vary by Android version and device; use the in-app Stop listening control to disable it immediately.

### 📋 Smart Clipboard Intelligence
- Clipboard access is on-demand only: HOPE does not monitor or poll clipboard changes. Ask “What did I copy?”, “Explain my copied text”, “Iska reply kya dena chahiye?”, “Translate my copied content”, or “Copied image explain karo”, or tap **Check Clipboard**.
- Text and image analysis have separate settings. Password-, OTP-, token-, and payment-like text is blocked by local best-effort checks; do not request analysis of images containing credentials or payment details. Suggested replies are never sent to other apps.
- Android 10+ can block clipboard access while HOPE is in the background, and a foreground service does not bypass this restriction. HOPE explains the limitation and offers a notification that opens the visible clipboard screen; if notifications are unavailable, open HOPE and repeat the request.
- Spoken responses use HOPE's shared speech engine. Clipboard history is off by default; enabling it stores a bounded history in encrypted preferences, and turning it off clears that history.
- Clipboard settings and the entry point are under **Settings → AI Features & Cloud Identity**. The clipboard screen uses the unchanged original `orb.html` animation.

### ☁️ Supabase foundation
- Supabase is used as the backend for optional long-term conversation memory; users do not create a separate Supabase account. HOPE reuses the existing Firebase sign-in and scopes transcript access through Firebase third-party JWT verification and owner-only RLS.
- When enabled with consent in **Settings → Neural Memory Bank**, HOPE backs up up to 100 locally retained turns already associated with the current Firebase account once, then stores completed text chats and regular voice transcripts in Supabase and asks Mem0 to extract searchable memories. Older untagged history is not copied. Clipboard and automation-only turns are excluded; audio is not stored. Cloud memory is off by default and includes controls to pause and clear cloud data.
- The new Memory & Knowledge dashboard adds per-message source references, structured and versioned facts, project records, a knowledge graph, paginated authorized history import/export, correction/deletion controls, full-text plus Mem0 retrieval, and database-backed health counts. The 20261114 migrations and matching Edge Function must be deployed before these additions are live. Imported records retain declared timestamp provenance; unknown imported timestamps are labeled unverified. Export checksums are recorded but are not a verified server backup, and the importer reprocesses source turns rather than restoring every derived record exactly.
- The project URL/publishable key are public client values. Firebase project ID and `MEM0_API_KEY` belong in Supabase Function Secrets; the service-role key is used only inside the Edge Function and never in Android. The memory function verifies Firebase ID tokens and scopes database operations to the verified Firebase UID. Deploy both database migrations and the Edge Function, then test with two Firebase accounts. See [SUPABASE_SETUP.md](./SUPABASE_SETUP.md).
- Existing Gemini requests remain client-side to preserve current functionality. The optional Edge text gateway is not yet a replacement; client-held Gemini keys remain extractable and Gemini Live has not been proxied.

### 🔒 Core Behaviour account isolation
- Core Behaviour rules, application history, and deletion markers are stored and queried with the active Firebase UID. Local edits and cloud outbox uploads reject records belonging to another account.
- Room schema migration 3→4 quarantines previously unscoped Core Behaviour rows under a reserved legacy owner instead of silently assigning them to whichever account signs in next. Those old rows are not visible or synced for any account; a safe, authenticated recovery flow has not been implemented.
- Supabase Core Behaviour tables are restricted to `service_role`; the Edge Function must verify Firebase identity and use the verified UID. Deploy and test the updated function/migration with two accounts before enabling cloud sync in production.
- The Android Room database remains app-private but is not encrypted with SQLCipher. Device compromise/root access is outside the account-isolation protection described above.

### 🤖 Cloud Agent Tasks — new in 2.0.0
- Submit an objective through the opt-in Cloud Agent screen, review task state and ordered events, and pause, resume, or cancel tasks.
- A Kotlin/JVM Google ADK worker produces a bounded plan; it has no callable device tools. Android independently validates every proposed tool call.
- Coding requests use a separate stateless Gemini provider and return validated file proposals or clarification questions. These proposals remain review-only: the worker does not write files, execute code, or claim tests/builds passed.
- The current local bridge is restricted to device-context reads and lowering screen brightness. Every proposal requires explicit approval; brightness changes require Android's special settings access and read-back verification.
- Consent is separate from Cloud Memory consent. Objectives are sent to Gemini only after Cloud Agent consent; device context is sent only after approval of that specific request.
- **Deployment status:** the gateway, migrations `202611120003` and `202611120004`, and Cloud Run worker are not deployed. Cloud tasks will not progress end-to-end until deployment and server-side configuration are completed.
- The worker does not retrieve Mem0 memory and its ADK session state is in-memory only. See the [detailed implementation guide](./docs/architecture/cloud-agent-implementation.md).

### 🎙️ Voice assistant
- Talk with HOPE using the app’s voice controls and supported live voice experience.
- Configure the assistant persona and available voice settings.
- Use HOPE as Android’s default digital assistant after selecting it through Android Settings.
- Voice features depend on device support, permissions, network access, and Gemini configuration.

### 📱 HOPE Phone
- Use the Dial Pad, Contacts, and Recent Calls screens.
- Access device contacts and call history when the required Android permissions are granted.
- Place cellular calls through Android Telecom.
- Open the dialer directly from the separate orb-style **HOPE Call** launcher icon, even when HOPE's voice assistant service is off. Add it to the Home screen from the launcher if desired; HOPE must be the default phone app to use its full dialer.
- Use supported in-call controls, including mute, speaker, keypad, and hold.
- See call screens update from Android Telecom’s actual call state.
- Incoming-call voice confirmation is optional. HOPE does not record or transcribe cellular call audio.

### 📞 Redesigned call screens — new in 1.9.3
- Incoming calls show the HOPE ORB above the caller name and number, with prominent Answer and Decline controls.
- Outgoing and active call screens keep the ORB centered, show the real Telecom call state, and retain supported in-call controls.
- Answer/Decline and in-call controls stay in a bottom-pinned control area instead of scrolling offscreen; outgoing calls show Mute and supported Speaker controls while connecting.
- The call screen uses a responsive dark layout and text-first controls without emoji icons.
- Caller details and call state come from Android Telecom; sample details are used only in the visual preview.

### ✨ HOPE Call ORB — new in 1.9.2
- Updated call screens with a particle-style HOPE ORB.
- ORB status follows incoming, connecting, active, held, and ended call states.
- Listening status is shown when HOPE’s incoming-call voice recognition is actually active.
- Three.js is bundled in the app for offline rendering; a visual fallback is available when WebGL cannot initialize.

### 🔔 Notification Inbox and announcements
- View notifications Android has actually delivered to HOPE, with inbox history and read status.
- Configure notification monitoring and announcements per app.
- Use the shared HOPE announcement voice path, with available controls for announcement behavior.
- Notification access must be granted in Android Settings. Supported content depends on notifications received from other apps.

### 💬 Auto-Reply
- Automatic sending is off until the user explicitly enables the relevant settings and rules.
- Replies are limited to selected apps and supported Android inline-reply actions.
- Unsupported reply actions, disabled permissions/settings, and safety exclusions prevent sending.
- Reply previews and spoken replies are separate from sending a message.
- Ask HOPE by text or voice to start, stop, or check WhatsApp auto-reply; starting verifies Notification Access, monitoring, and AI configuration. Smart Auto-Reply needs no rule; rule-based sending still requires an enabled WhatsApp `AUTHORIZED_AUTO_REPLY` rule.
- Select HOPE, FRIDAY, E.D.I.T.H., or ULTRON for WhatsApp without changing the app-wide persona. Text/voice commands can select the WhatsApp persona or enable, disable, and check ULTRON Auto-Defense.
- ULTRON Auto-Defense is a separate opt-in, WhatsApp-only feature. It uses the configured AI service to classify recent context, fails closed below the selected confidence threshold, excludes quoted/reported or playful abuse, and keeps each takeover conversation-specific with a 5–60 minute expiry. It must not be used to send personal attacks, threats, or slurs.
- Auto-Defense stores only a hashed conversation key and session metadata locally; the classifier still receives recent message context through the configured AI service. No Supabase migration was added for this local-only state.
- Message replies use HOPE's configured AI router when AI replies are enabled, or the configured authorized-rule/template reply otherwise. Messages are screened for sensitive content and hashed fingerprints prevent recent duplicate submissions.
- Android must provide a supported notification `RemoteInput` action. HOPE reports the action submission, not confirmed recipient delivery; WhatsApp does not expose a universal personal-account send API to this app.
- Notification callbacks may run with the screen off, but Android/OEM process limits can delay or stop processing. Pending replies are in-process work and are not guaranteed to survive process termination.

### 📸 Voice screenshot
- Ask HOPE to take a screenshot, including **“screenshot le lo”**.
- Screenshot capture depends on Android version and the required Accessibility or user-approved Screen Share access.
- Screenshots are saved locally; the feature does not send them to Gemini.

### 🤖 On-screen tasks
- HOPE can assist with supported app and screen tasks using Android Accessibility and visual assistance features.
- Automation depends on the target app exposing usable screen controls and the required system access being enabled.
- HOPE may stop or ask for help when an action cannot be verified.

## 🔐 Permissions and privacy

HOPE’s system integrations require the relevant Android permissions or user-enabled services. This may include microphone, Contacts, Phone, Notification Access, Accessibility, overlay, and Screen Share access, depending on the feature used. Hey HOPE standby, voice clipboard, Smart Clipboard, image analysis, and speaker-personalized addressing are separate opt-ins. Smart Clipboard reads clipboard content only after an explicit voice request or user action; text and image analysis have independent settings. Sensitive-text checks are best-effort, and images should not contain credentials or payment details. Android 10+ background clipboard restrictions are respected; a foreground service does not bypass them. Clipboard history is off by default and is stored only when explicitly enabled using encrypted preferences. Standby and speaker embeddings use bundled Vosk models on-device; the active conversation still uses the configured HOPE/Gemini voice service. With personalized addressing enabled, the selected profile's preferred address and language are sent to that configured AI service as context. Voice embeddings are not uploaded. Explicit dictated-text copying uses Android ClipboardManager. Clipboard operations that interact with other apps require the user to enable HOPE Accessibility and separately opt in to voice clipboard in the app. HOPE does not retain enrollment recordings.

The HOPE app-lock verifier and encrypted security settings remain on-device. App-lock credentials are entered only in the security screen and are not included in AI prompts, notification content, analytics, or cloud requests. Notification history and screenshots are handled on-device where supported. AI features that use Gemini require network access and may send the content needed to answer your request to the configured Gemini service. Review the in-app settings and Android permission prompts before enabling integrations.

## 🆕 HOPE AI 3.0.2

The Android app version is **3.0.2** (version code **157**). Version 3.0.2 adds a separate **HOPE Call** launcher icon with orb-style artwork, launching the existing dialer without starting the main voice assistant service. Incoming, outgoing, and active call screens now use the same bundled WebGL orb renderer as the main app. Android requires the user to add the launcher entry to the Home screen; the full dialer requires HOPE to be selected as the default phone app.

## HOPE AI 3.0.1

The Android app version is **3.0.1** (version code **156**). Version 3.0.1 adds a rights-confirmed shared-media downloader:

- Share a link to HOPE from another app or paste a direct HTTPS media-file URL.
- Ask HOPE to download the video or get the audio/song. A voice request starts the download after you confirm that you own the media or have permission.
- Direct audio/video files up to 250 MB are supported. HOPE verifies the response as playable media; supported AAC/AMR audio tracks can be extracted from a direct video file to M4A.
- Files save to Android's Movies/Music library on Android 10+, or HOPE's private app storage on older versions. Use **Share saved media** to choose a destination.
- Streaming-platform pages, DRM-protected media, non-HTTPS links, and unsupported codecs are not downloaded. HOPE does not bypass platform restrictions, and it never sends a file to another person without your explicit share choice.

## HOPE AI 3.0 highlights

The version 3.0 foundation (version code **155**) includes:

- **Authentication:** improved login and signup field contrast, dark/light styling, password visibility, strength guidance, and validation while preserving the existing Firebase and Google sign-in and password-reset flows.
- **Smart Auto-Reply:** uses up to five available conversation messages, adapts tone and language, and introduces HOPE once per saved conversation. Sensitive or high-impact notifications are not automatically sent.
- **Contact reply profiles:** optional contact-specific reply behavior with suggest-only and limited auto-reply modes. Replies depend on Android-provided notification quick-reply actions; supported apps vary, and action submission does not confirm delivery.
- **Voice reliability:** adds text-to-speech fallback for completed turns without generated audio and guards against stale microphone, playback, and WebSocket callbacks.
- **Memory & Knowledge:** adds source-linked history, versioned facts, project and graph records, search, correction/deletion, and resumable archive import/export. Backend migrations and the Edge Function must be deployed before these cloud features are live. An export is not a verified server backup, and import rebuilds derived memory from source turns.
- **Cloud Agent coding proposals:** coding requests can return validated file proposals for review; the provider does not write or execute files. The gateway and worker still require deployment for end-to-end Cloud Agent use.

See [CHANGELOG.md](./CHANGELOG.md) for details and known limitations.

## Previous changes in version 2.0.0

- Added Cloud Agent Phases 1–3: consent-gated task gateway, Kotlin/JVM ADK planning worker, Android task review screen, and a confirmation-based local tool bridge.
- Added database-enforced task ownership, quotas, idempotency, ordered events, expiring tool requests, atomic plan publication, and consent revocation handling.
- Restricted device actions to context reads and lowering brightness; actions require explicit approval, platform permission checks, and read-back verification.
- Updated the app version to **2.0.0** (version code **153**).
- Cloud Agent backend components remain source-only and must be deployed before tasks can execute end-to-end. See [CHANGELOG.md](./CHANGELOG.md) for the full release history and validation status.

## ✅ Verification

- Android unit tests: **passed** (196 tests) with `.\gradlew.bat :app:testDebugUnitTest`
- Android lint: **passed** with `.\gradlew.bat :app:lintDebug`; 1,044 warnings and no errors.
- Debug APK build: **passed** with `.\gradlew.bat :app:assembleDebug`
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`; debug-key signed and not suitable as a production release.
- Version 3.1.3 changes Updates into a filterable, compact feed with expandable release details and a separate pending-work filter.
- Version 3.1.4 redesigns Settings with live search and category filters for AI & Voice, Device, Automation, and Security.
- Version 3.1.5 removes the Gemma offline inference path and routes AI and Live voice through Gemini Online; Vosk wake-word detection remains local.
- Version 3.1.2 routes clipboard text through the selected AI provider and blocks several direct cloud-AI paths in local-only mode. The larger local-runtime migration is still pending; see the Local AI status above and [CHANGELOG.md](./CHANGELOG.md).
- Version 3.1.1 expands the in-app Updates screen with detailed release notes, filters sensitive history before AI requests, and includes the latest memory, Core Behaviour, social-reply, login, and licence reliability work.
- Version 3.1.0 adds opt-in, conversation-scoped ULTRON Auto-Defense for WhatsApp, confidence and timeout controls, plus WhatsApp-only voice/text persona commands. See [the persona engine notes](./docs/PERSONA_ENGINE.md).
- Version 3.0.8 refines the four existing persona behaviors and remembers a supported Gemini voice independently for each persona. HOPE remains the default. Voice preview and independent pitch/rate controls are not available in the current integration.
- Version 3.0.9 lets ULTRON use pointed, playful roasts about low-stakes choices or reasoning when appropriate, while avoiding sensitive situations and stopping if the tone is unwanted.
- Version 3.0.7 assigns distinct identities and behavior profiles to HOPE, FRIDAY, E.D.I.T.H., and ULTRON across persona-aware AI paths; HOPE remains the default. See [the persona engine notes](./docs/PERSONA_ENGINE.md).
- Version 3.0.6 was installed on an API 37 emulator; MainActivity opened after a cold restart with saved licence state and stayed open during Light/Dark theme changes. Physical-device testing and fresh licence activation remain pending.
- Cloud Agent worker tests and distribution build: **passed** with `.\gradlew.bat -p cloud-agent-runtime test installDist`
- Eight memory policy tests and Edge Function TypeScript syntax checks: **passed** using a Node-compatible harness
- Deno type-check, Supabase migration execution/deployment, Cloud Agent deployment, and authenticated cloud integration: **not performed**
- Device tests for WhatsApp notification formats, inline reply availability and acceptance, screen-off processing, process recreation, Android Keystore persistence, biometrics/recovery UI, app lifecycle relocking, OS keyguard requests, clipboard interoperability, wake false activations, and Android/OEM background limits: **not performed**

### Remaining production work
- The Android app still needs an authenticated two-account migration/sync test. Existing unscoped Core Behaviour rows are quarantined; there is no authenticated recovery screen.
- There is no durable Pending Decisions queue with edit/approve/send, no Gmail OAuth/module, and no contact-priority ranking. WhatsApp remains limited to accessible notifications and supported inline-reply actions.
- Gmail/other-channel deterministic decision gates, the complete proactive briefing/activity timeline, and full autonomous tool execution are not production-complete. Supabase migrations/functions and Cloud Agent services require authorized deployment and credentials.
- A release APK requires the verified publisher identity, release signing key, and certificate fingerprint; these are intentionally not included in the repository.

## 📲 Install

The published GitHub APK may not yet include the 3.1.5 source update. Build the current source locally to use the latest Gemini-only AI routing, Settings hub, Updates feed, persona, Auto-Defense, social-reply, memory, theme, and licence-startup fixes. Debug APKs are signed with Android's debug key and are intended for testing, not as production release builds:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. It is not a release-signed APK. Install on Android 8.0 (API 26) or later; Android may ask you to allow installation from the source used to download the APK.

## 🏷️ Topics

`hope-ai` · `android` · `voice-assistant` · `phone-dialer` · `notifications` · `auto-reply` · `media-download` · `accessibility` · `security-manager` · `speaker-recognition` · `cloud-agent` · `v3.1.5`

---

<p align="center">
  Made for Android with Kotlin and HOPE.
</p>
