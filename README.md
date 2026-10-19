<h1 align="center">✨ HOPE AI — Your Voice Assistant</h1>

<p align="center">
  <strong>Talk to HOPE, get help with everyday tasks, manage notifications, and handle calls — all from one Android app.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-1.9.3-4169E1" alt="Version 1.9.3">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Package-com.jarvis.assistant-6A5ACD" alt="Android package">
</p>

## 👋 About HOPE AI

HOPE is an Android voice assistant with conversational voice features, phone and contact tools, notification intelligence, and on-screen task automation. It combines Gemini Live and Android voice capabilities with system integrations that work when the required permissions and services are enabled.

## 🌟 App features

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

### 📸 Voice screenshot
- Ask HOPE to take a screenshot, including **“screenshot le lo”**.
- Screenshot capture depends on Android version and the required Accessibility or user-approved Screen Share access.
- Screenshots are saved locally; the feature does not send them to Gemini.

### 🤖 On-screen tasks
- HOPE can assist with supported app and screen tasks using Android Accessibility and visual assistance features.
- Automation depends on the target app exposing usable screen controls and the required system access being enabled.
- HOPE may stop or ask for help when an action cannot be verified.

## 🔐 Permissions and privacy

HOPE’s system integrations require the relevant Android permissions or user-enabled services. This may include microphone, Contacts, Phone, Notification Access, Accessibility, overlay, and Screen Share access, depending on the feature used.

Notification history and screenshots are handled on-device where supported. AI features that use Gemini require network access and may send the content needed to answer your request to the configured Gemini service. Review the in-app settings and Android permission prompts before enabling integrations.

## 🆕 Changes in version 1.9.2

- Added the new particle-style HOPE Phone call ORB.
- Connected ORB status to real Android Telecom and voice-recognition states.
- Bundled Three.js so the ORB does not rely on a remote CDN.
- Updated the app version to **1.9.2** (version code **148**).

## ✅ Verification

- Debug APK build: **successful**
- Unit tests: **33 passed**
- Physical-device call-screen visual test: **not performed**

## 📲 Install

Download the APK from **Assets** below and install it on a device running Android 8.0 (API 26) or later. Android may ask you to allow installation from the source you used to download the APK.

> **Release maintainer:** Attach a release-signed APK to this GitHub Release before publishing. The verified APK from this build is a debug APK, not a release-signed APK.

## 🏷️ Topics

`hope-ai` · `android` · `voice-assistant` · `phone-dialer` · `notifications` · `auto-reply` · `accessibility` · `v1.9.3`

---

<p align="center">
  Made for Android with Kotlin and HOPE.
</p>
