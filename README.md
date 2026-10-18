# HOPE Voice Assistant

An Android voice assistant built with Kotlin, Gemini Live, on-device voice components, accessibility automation, screen capture, and a native Android UI.

## Project status

- Android application module: `app`
- Application ID: `com.jarvis.assistant`
- Minimum Android version: Android 8.0 (API 26)
- Compile and target SDK: 34
- Java/Kotlin target: 17
- Current app version: 1.8.7 (version code 143)

## Getting started

1. Open the project root in Android Studio and allow Gradle to sync.
2. Set the Android SDK path in your local `local.properties` file. The file is machine-specific and is ignored by Git.
3. Launch the app on an Android 8.0+ device or emulator.
4. Add a Gemini API key in the app's Settings and grant the requested runtime permissions.

The Gradle wrapper is included in this project. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux.

## Build commands

```powershell
# Windows: compile and package a debug APK
.\gradlew.bat :app:assembleDebug

# Windows: compile and package a release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs are written under `app/build/outputs/` and are generated files, not source files. Release builds enable R8 and resource shrinking. `assembleRelease` intentionally refuses to package unless a dedicated keystore, its SHA-256 certificate pin, and publisher metadata are configured. Never commit keystores or passwords.

Create and protect a release key outside this repository, then supply these values only in the local shell or Gradle user properties:

```powershell
$env:JARVIS_RELEASE_STORE_FILE = 'C:\secure\jarvis-release.jks'
$env:JARVIS_RELEASE_STORE_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_KEY_ALIAS = 'jarvis-release'
$env:JARVIS_RELEASE_KEY_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_CERT_SHA256 = '<64 hex characters from keytool -list -v>'
$env:JARVIS_DEVELOPER_NAME = 'TechGpt X'
$env:JARVIS_COPYRIGHT_OWNER = 'TechGpt X'
.\gradlew.bat :app:assembleRelease
```

The certificate fingerprint must be calculated from the same key used to sign the APK. This project currently has no deployed verification backend or agreed verification API protocol. Local signer pinning and APK signatures do not provide server-side authentication, Play Integrity attestation, or rate limiting; the app identity screen reports remote verification as unavailable until that service is implemented and deployed.

## Repository layout

```text
.
├── app/                         Android application module
│   └── src/main/
│       ├── java/com/jarvis/assistant/
│       │   ├── audio/           Audio capture, playback, and voice handling
│       │   ├── automation/      Accessibility, actions, and app integrations
│       │   ├── background/      Conversation foreground service
│       │   ├── data/            Preferences, persistence, and data models
│       │   ├── network/         Gemini Live transport
│       │   ├── ui/              Activities, fragments, and adapters
│       │   ├── util/            Shared app utilities
│       │   └── vision/          Screen capture and visual task execution
│       ├── assets/              Bundled web UI and offline voice models
│       └── res/                 Android layouts, drawables, values, and config
├── docs/
│   ├── architecture/            Design notes and research
│   └── diagnostics/             User-provided issue recordings and index
├── gradle/                      Gradle wrapper configuration
├── build.gradle.kts             Root Gradle plugin declarations
├── settings.gradle.kts          Project and repository configuration
└── gradle.properties             Gradle runtime settings
```

## Documentation

- [Documentation index](docs/README.md)
- [Voice architecture research](docs/architecture/voice-architecture-research.md)
- [Voice issue recordings](docs/diagnostics/voice-videos/README.md)

## Configuration and privacy

- Keep API keys, SDK paths, signing keys, and local machine settings out of source control.
- `local.properties` is local-only; start from `local.properties.example` when setting up a new machine.
- Screen sharing and accessibility automation require explicit Android user consent and permissions.
- HOPE can be selected as the Android default assistant from Settings. Android requires the user to approve the assistant role; invoking the system assistant opens HOPE. Always-on hotword activation is not provided by this role integration.
- **HOPE Phone → Dial pad / Contacts / Recent** uses Android Telecom, Contacts Provider, and Call Log data. Contacts and call history are requested only when their screens are opened; contact write access is requested only when changing a favorite. Recent call filters preserve individual call-log entries, and unknown numbers can be saved through the installed contacts app. HOPE must be approved as the Android default phone app for its in-call UI and cellular call controls; device, SIM, Android permission, and carrier behavior still require on-device verification.
- Notification intelligence is opt-in. Grant HOPE's Android **Notification access** from Settings → AI Chat Auto-Reply → **Grant HOPE notification access**. The dedicated `NotificationListenerService` can keep up to 100 app-private local history entries for individually selected apps, announce eligible notifications with Android Text-to-Speech, and apply deterministic local rules. Notification reading, announcements, and auto-replies have separate controls. Auto-reply is off by default; enable the master switch, an app, and either per-chat opt-in or the explicit reply-to-all setting. AI-generated replies are separately disabled by default; when enabled, recent notification message context from an authorized chat may be sent over HTTPS to the configured Gemini service. Fixed-template replies remain on-device. HOPE sends only through a notification-provided Android quick-reply action, does not open chats or tap Send, and skips credential/payment-related messages or notifications without a supported reply action. Message bodies are omitted from execution logs. Android Accessibility access is not required for notification reading or these notification quick replies. Notification formats, sender extraction, TTS language availability, and third-party reply actions vary by app/device and require real-device verification.
- Incoming-call announcement is configured separately in **Settings → Incoming Call Announcement**. The user must approve HOPE as Android's default phone app; Android then routes Telecom calls to HOPE's `InCallService`. Incoming UI provides explicit Answer/Reject controls, and optional voice confirmation asks first and listens briefly for a clear “answer”/“reject” (Hindi commands are supported). No clear command or a recognition timeout leaves the call ringing. Voice commands require microphone permission and use the installed Android speech-recognition service; offline recognition is requested but is not guaranteed. Caller name/number is announced only if the device exposes it. This dialer implementation has only been build/unit tested; verify SIM, VoIP, lock-screen, ringtone, and speech behavior on target phones before relying on it. Users can restore the prior dialer at any time from Android's default-app settings.
- Bundled Vosk model assets are runtime dependencies; do not remove or rename their directories without updating the model-loading code.

## On-device Gemma chat

Open **Settings → On-device Gemma & AI Routing** to choose Online, Offline, or Automatic chat, edit the chat system instruction, and manage Gemma 3 1B.

- The app uses Google's LiteRT-LM Android runtime `0.16.1` and the LiteRT Community Gemma 3 1B instruction-tuned 4-bit artifact `Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` at the pinned Hugging Face revision `d448835d84411a08f577e25f3afebe528402d73b`. The model is about 557 MiB; allow at least 2 GiB of free storage for safe downloading/redownloading.
- The model repository is gated. Review and accept the [Gemma terms](https://ai.google.dev/gemma/terms) and model access terms on the [Hugging Face model page](https://huggingface.co/litert-community/Gemma3-1B-IT). You can either provide a Hugging Face read token for HOPE's resumable download, or download the pinned `.litertlm` file in a browser and use **Import downloaded .litertlm file**. Browser download and import does not require entering a token in HOPE. HOPE stores any supplied token using Android encrypted preferences and sends it only to Hugging Face. Before installation/loading, both download methods check the model's exact size and SHA-256; the token-based download also checks the pinned Hugging Face revision and file Git object ID. Delete removes the model and partial file.
- **Offline** uses only the local Gemma engine for typed chat; it never calls Gemini. **Online** sends the current prompt and recent chat context to Gemini. **Automatic** uses Gemini when it is available and cloud use is enabled, and falls back to the local model if Gemini fails; turn off **Allow cloud use in Automatic mode** to keep Automatic chat local-only. The assistant message identifies its generating engine.
- The app supports LiteRT-LM CPU inference (two threads) and offers the GPU backend where the device/runtime supports it. If GPU initialization fails, HOPE automatically retries with CPU and updates the selected backend; model-load errors are shown in the settings screen. Model loading checks available memory and runs off the UI thread. The app requires at least 4 GiB total device memory and 1.3 GiB currently available before attempting to load; a 6 GiB-or-more phone and additional free memory are recommended, but no universal device guarantee is possible.
- The runtime supports Android API 24+, while HOPE itself requires Android API 26+. Gemma uses Google's Gemma terms; the LiteRT-LM runtime is Apache-2.0. Download and real-device inference require a network connection/model access initially; no model is bundled in the APK.
- This routing applies to typed chat. The existing voice/live conversation and notification auto-reply paths retain their existing Gemini behavior. The chat history remains in the app's existing local preferences store. No real-device download or inference is claimed unless separately tested on a device with authorized model access.


For build verification, run `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:assembleDebug`.
