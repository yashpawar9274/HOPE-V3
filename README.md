<h1 align="center">✨ HOPE AI — Your Voice Assistant</h1>

<p align="center">
  <strong>Talk to HOPE, get help with everyday tasks, manage notifications, and handle calls — all from one Android app.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-1.9.6-4169E1" alt="Version 1.9.6">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Package-com.jarvis.assistant-6A5ACD" alt="Android package">
</p>

## 👋 About HOPE AI

HOPE is an Android voice assistant with conversational voice features, phone and contact tools, notification intelligence, and on-screen task automation. It combines Gemini Live and Android voice capabilities with system integrations that work when the required permissions and services are enabled.

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

### 🎙️ Voice assistant
- Talk with HOPE using the app’s voice controls and supported live voice experience.
- Configure the assistant persona and available voice settings.
- Use HOPE as Android’s default digital assistant after selecting it through Android Settings.
- Voice features depend on device support, permissions, network access, and Gemini configuration.

### 📱 HOPE Phone
- Use the Dial Pad, Contacts, and Recent Calls screens.
- Access device contacts and call history when the required Android permissions are granted.
- Place cellular calls through Android Telecom.
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
- Ask HOPE by text or voice to start, stop, or check WhatsApp auto-reply; starting checks Notification Access, notification monitoring, an enabled WhatsApp `AUTHORIZED_AUTO_REPLY` rule, and AI configuration before enabling WhatsApp monitoring.
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

HOPE’s system integrations require the relevant Android permissions or user-enabled services. This may include microphone, Contacts, Phone, Notification Access, Accessibility, overlay, and Screen Share access, depending on the feature used. Hey HOPE standby, voice clipboard, and speaker-personalized addressing are separate opt-ins. Standby and speaker embeddings use bundled Vosk models on-device; the active conversation still uses the configured HOPE/Gemini voice service. With personalized addressing enabled, the selected profile's preferred address and language are sent to that configured AI service as context. Voice embeddings are not uploaded. Explicit dictated-text copying uses Android ClipboardManager. Clipboard operations that interact with other apps require the user to enable HOPE Accessibility and separately opt in to voice clipboard in the app. HOPE does not retain clipboard history or enrollment recordings.

The HOPE app-lock verifier and encrypted security settings remain on-device. App-lock credentials are entered only in the security screen and are not included in AI prompts, notification content, analytics, or cloud requests. Notification history and screenshots are handled on-device where supported. AI features that use Gemini require network access and may send the content needed to answer your request to the configured Gemini service. Review the in-app settings and Android permission prompts before enabling integrations.

## 🆕 Changes in version 1.9.6

- Added speaker profiles, explicit spoken consent for enrollment, device-authenticated profile management, three-sample local x-vector enrollment, configurable conservative match thresholds, and neutral UNKNOWN/ambiguous handling.
- Added Keystore-backed AES-GCM encryption for speaker embeddings and profile metadata; enrollment audio is not persisted.
- Added opt-in personalized addressing in the existing live conversation path without changing HOPE's configured voice; disclosure explains that preferred address and language are sent to the configured AI service.
- Added explicit text and voice commands to start, stop, and check WhatsApp auto-reply, with prerequisite checks, persistent opt-in, pending in-process cancellation on stop, duplicate protection, and visible last-result status.
- Reused HOPE's configured AI router for AI-generated replies, added bounded retries and send-failure reporting, and re-checks permissions, monitoring, rule authorization, quiet hours, and exclusions before submitting a reply action.
- On notification-listener reconnection, existing active notifications are recorded without being treated as new messages for auto-reply.
- Updated the app version to **1.9.6** (version code **152**).

## Previous changes in version 1.9.5

- Added the HOPE-only app lock with PIN, pattern, and password credentials, change/disable/recovery flows, timed lockout, configurable background timeout, and secure-window protection.
- Added a PBKDF2-HMAC-SHA256 password verifier and Android Keystore-backed AES-GCM local security storage.
- Added optional BiometricPrompt unlock and a voice action that requests Android's keyguard UI without collecting or entering device credentials.
- Added opt-in voice clipboard commands for supported selections, links, focused editable fields, and select-all, with truthful verification and no clipboard history.
- Added local Hey HOPE standby listening, a configurable confidence threshold, explicit standby commands, foreground-service disclosure, and a manual stop/wake control.
- Deferred loading the offline Vosk models until a voice feature is explicitly requested.

## ✅ Verification

- Debug APK build: **passed** with `.\gradlew.bat assembleDebug`
- Unit tests: **passed** with `.\gradlew.bat testDebugUnitTest`
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`
- Device tests for WhatsApp notification formats, inline reply availability and acceptance, screen-off processing, process recreation, Android Keystore persistence, biometrics/recovery UI, app lifecycle relocking, OS keyguard requests, clipboard interoperability, wake false activations, and Android/OEM background limits: **not performed**

## 📲 Install

Download the APK from **Assets** below and install it on a device running Android 8.0 (API 26) or later. Android may ask you to allow installation from the source you used to download the APK.

> **Release maintainer:** Attach a release-signed APK to this GitHub Release before publishing. The verified APK from this build is a debug APK, not a release-signed APK.

## 🏷️ Topics

`hope-ai` · `android` · `voice-assistant` · `phone-dialer` · `notifications` · `auto-reply` · `accessibility` · `security-manager` · `speaker-recognition` · `v1.9.6`

---

<p align="center">
  Made for Android with Kotlin and HOPE.
</p>
