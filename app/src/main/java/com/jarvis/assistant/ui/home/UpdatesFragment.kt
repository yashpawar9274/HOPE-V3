package com.jarvis.assistant.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.databinding.FragmentUpdatesBinding

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        renderFeatureUpdates()
        return binding.root
    }

    private data class FeatureNotice(val feature: String, val title: String, val details: String, val kind: String = "FEATURE")

    private fun renderFeatureUpdates() {
        val features = listOf(
            FeatureNotice(
                "HOPE AI 3.0",
                "Advanced Memory & Knowledge dashboard",
                "Cloud Memory now has source-linked conversation messages and structured, versioned facts; project records, entities, relationships, and conversation summaries; full-text plus Mem0 semantic retrieval; correction and deletion controls; and database-backed counts, indexing health, and import status. You can import an authorized HOPE/hope-history-v1 JSON archive in resumable batches or export a paginated JSON archive with a SHA-256 checksum. Unavailable history is not fabricated. An export checksum is recorded but is not a verified server backup, and re-import currently rebuilds structured facts from source turns rather than restoring every derived record. Apply the new Supabase memory migration and deploy the updated `hope-memory` function before using these features."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "Cloud Agent coding proposals",
                "Cloud Agent can route software-development requests to a stateless Gemini coding provider, validate structured output and safe relative file paths, and show up to five file proposals or clarification questions. An explicitly configured fallback is used only when the primary model returns HTTP 404. Proposals are saved to task history only; HOPE does not write project files, execute code, or claim builds/tests/deployments succeeded. The updated runtime and required server-side model configuration must be deployed before this is available in production."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "Context-aware Smart Auto-Reply",
                "HOPE introduces itself with the exact first-contact message “Hey, I'm HOPE 😼” once per saved conversation, uses up to the latest five available messages, and adapts reply style to conversational tone and configured contact preferences. Sensitive or high-impact messages are held for manual review rather than auto-sent. Context remains local with notification-history retention controls. Automatic sends still use only authorized Android notification quick-reply actions; WhatsApp, Instagram Direct, and SMS are supported when an action is available. Telegram and Facebook Messenger are unsupported, and Android cannot confirm recipient delivery."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "Smart Auto-Reply overview and context controls",
                "The Auto-Reply screen now shows retained-message and recent-chat counts, submitted reply-action counts, current status, supported integrations, and recent activity. Clearing local conversation context also clears first-contact state; HOPE may introduce itself again after that explicit reset. Suggest-only replies are not sent automatically; a separate approve/send queue is not available."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "Clearer login and signup",
                "Authentication fields now have clearer text, labels, and light/dark theme styling. Passwords can be shown or hidden; signup includes full name, password confirmation, strength guidance, and clearer validation. Existing Firebase email/password, Google sign-in, and password-reset flows are preserved, and the signup name is saved to the Firebase profile."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "More reliable conversation context on Android 8+",
                "Notification conversation messages are now extracted with AndroidX compatibility APIs, avoiding Android 11-only message parsing on devices supported from Android 8."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "Voice response fallback and session protection",
                "If Gemini Live provides a text response without generated audio, HOPE can speak that response with Android TextToSpeech. Microphone, playback, and Gemini WebSocket callbacks from an ended conversation are ignored. New installs use Gemini 3.8 Live by default; the legacy Gemini 3.1 Live preview remains available as a fallback."
            ),
            FeatureNotice(
                "HOPE AI 3.0",
                "More careful voice tone adaptation",
                "Voice conversation guidance now treats emotional cues as uncertain and encourages calm, supportive phrasing without claiming to know exactly how the user feels."
            ),
            FeatureNotice(
                "HOPE 2.0.1",
                "Contact-specific Smart Auto-Reply",
                "Choose per-conversation behavior: Disabled, Suggest only, or Limited auto-reply after explicit confirmation. A saved contact profile overrides the existing global Smart Auto-Reply fallback. Profiles and discovered identity labels stay in a separate local database; message text is not copied into profile records. HOPE uses a hashed conversation/phone identity when Android provides one, otherwise a notification-instance identity, and never merges people by display name alone. Replies still use the original notification action and keep the existing sensitive-content, group-chat, quiet-hour, duplicate, permission, and app controls. Third-party notification action support varies by app and device."
            ),
            FeatureNotice(
                "HOPE 2.0.1",
                "Safer Cloud Agent device tools",
                "Cloud Agent requests now carry task, session, and tool-call identity, expiry, an exact authorized parameter set, permission scope, and risk category. HOPE validates the supported tool schema locally, asks for confirmation where needed, applies timeouts and permissions, and checks outcomes before reporting success. The device tool allowlist remains intentionally narrow; arbitrary shell commands, unrestricted code, message sending, and unsupported calendar/accessibility actions are not exposed. The Android app, gateway, and worker changes are source updates; the gateway and Cloud Run worker still need deployment and authenticated end-to-end testing before Cloud Agent works as a production cloud service."
            ),
            FeatureNotice(
                "HOPE 2.0.1",
                "Cloud Agent task controls",
                "Submit an opt-in task, review its status and event history, and pause, resume, or cancel it from the Cloud Agent screen. Cloud Agent consent is separate from Cloud Memory consent. Device context is disclosed only after approval of the specific request, and the planner cannot directly execute device actions."
            ),
            FeatureNotice(
                "HOPE 2.0.1",
                "More reliable Mem0 memory extraction",
                "The memory backend now has a per-user extraction-job queue with bounded attempts, expiring processing leases, and safe concurrent claiming. If a worker stops mid-job, an expired lease lets a later worker retry it. Per-user diagnostics track retrieval counts and deletion status without exposing transcript contents. This is a backend migration and requires the migration and worker to be deployed before these reliability improvements are active."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Smart Clipboard Intelligence",
                "Smart Clipboard now reads nothing until you explicitly ask HOPE what you copied or tap Check Clipboard. Voice requests can explain text, suggest replies, translate content, explain copied code, or analyze an image; text and image analysis have separate settings, and suggested messages are never sent. Its controls are under Settings → AI Features & Cloud Identity, not on Home. Sensitive-looking text is blocked locally, with best-effort detection. HOPE reuses its existing ORB and shared speech engine. Android 10+ may block clipboard access in the background; HOPE then explains the restriction and offers a notification to open the visible clipboard screen rather than bypassing OS rules. Optional clipboard history is off by default and uses encrypted storage when enabled."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Cloud Long-Term Memory with Mem0",
                "Supabase is HOPE's memory backend—there is no separate Supabase account screen. Cloud memory reuses the existing Firebase sign-in, is off by default, and asks for consent before backing up up to 100 local turns already associated with that Firebase user or saving later text chats and regular voice transcripts. The Edge Function verifies Firebase identity and scopes server-side database access to that UID; its service-role key never enters the app. Older untagged history is not copied. Mem0 extracts and searches relevant memories asynchronously. Voice audio, clipboard requests, and automation-only turns are excluded. Settings → Neural Memory Bank can pause cloud memory or clear transcripts and request Mem0 deletion. The production database migrations and memory function are deployed; authenticated save/search still needs an app-level test."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Calendar, alarms and reminders",
                "Time Manager reads and creates events in Android calendars, opens the installed clock app for alarms and timers, and stores one-time or recurring reminders with WorkManager. Calendar access and notification delivery require Android permissions. Reminder delivery is inexact and may be delayed by battery restrictions; event deletion and scheduling conflicts require explicit confirmation."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Login and licence key",
                "Sign in with Firebase (Email/Password or Google) and activate HOPE with your licence key. The key must belong to the same account that bought HOPE on the website; the server checks it and limits how many phones can use it. The licence is re-checked on every start and at least every 24 hours, and works offline for up to 72 hours from a signed licence token. The unlock screens use the same HOPE ORB as the home screen."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Auto-reply that sounds human, and says it is an AI",
                "WhatsApp, Instagram and SMS auto-replies are now short, casual one-liners that mirror the sender's language, length, tone and emoji. HOPE introduces itself in the first reply as the owner's AI assistant and answers honestly if anyone asks whether it is a bot. It never repeats itself, waits a moment like a person typing, skips group chats, and never claims to be human."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Permissions Center: notifications, files, location, calls",
                "New cards on the Permissions screen: read notifications, personal files and media (including all-files access), location all the time, and messages plus call info. GRANT ALL now walks through every missing permission one Android screen at a time. Android never lets an app grant these to itself; you switch them on, HOPE shows what is missing and why. An optional adb command for advanced system settings is shown too. HOPE does not record a location history."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Camera, microphone and calls",
                "HOPE can now request the camera and location foreground-service types when you have granted those permissions, so camera tasks work while HOPE runs in the background. Android gives the microphone to a normal phone call while it is active, so HOPE pauses listening during calls and cannot hear you on the phone; it resumes after the call."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Device settings and safe UPI payments",
                "Ask HOPE to open any settings screen (Wi-Fi, Bluetooth, airplane mode, display, sound, battery, location, apps, accessibility and more) or to change brightness, screen timeout and auto-rotate once Modify system settings is allowed. Android does not let apps flip Wi-Fi, Bluetooth or airplane-mode switches, so HOPE opens the right screen for you. Payments: HOPE can only prepare a UPI screen with the payee and amount after you clearly confirm them aloud; you approve with your own UPI PIN or fingerprint. HOPE never enters a PIN or OTP and never completes a payment. Payments above 1 lakh rupees are refused."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Meeting Mode",
                "When you are in a meeting, sleeping or busy, HOPE declines SIM calls without ringing, texts the caller from your own SIM, and keeps chatting with them over SMS or WhatsApp using your own instructions. Starred contacts and a second call within 5 minutes still ring. Set a time limit, see a log of what happened, or switch it on by voice. Android does not let apps speak inside a normal phone call, so HOPE answers by text."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Tasks with the screen off",
                "For tasks that need the screen (opening apps, tapping, typing, YouTube, WhatsApp, settings), HOPE switches the display on by itself and lets it sleep when the task ends. A phone locked with a PIN, pattern or fingerprint cannot be unlocked by an app, so HOPE asks you to unlock once and then continues."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Female voice grammar and speaking only when spoken to",
                "With a female voice, HOPE now uses feminine Hindi and Hinglish about herself. After her last reply HOPE listens for a 30 second follow-up, then returns to the wake word, and stays silent when you are talking to someone else instead of answering friends' conversations."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Default digital assistant",
                "HOPE is now set up so Android lets you choose it as the default digital assistant app; the power-button or home long-press assist gesture can then open HOPE."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "iPhone-style phone",
                "A new dialer with Keypad, Contacts and Recents (All, Missed, Incoming, Outgoing), and iPhone-style incoming, outgoing and active call screens with round Mute, Keypad, Speaker, Add call, Hold and End buttons. Calls show the caller's photo as a blurred background (or a colour from their name) with the HOPE ORB in the centre, banner notifications with a live timer, and the screen turns off at your ear using the proximity sensor."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "HOPE Smart Auto-Reply",
                "One switch turns on automatic replies for WhatsApp, Instagram and SMS with no templates: HOPE reads the message and writes a fresh reply for it. It skips OTP and bank messages, group chats and other auto-responders, has per-chat cooldowns and your daily limit, and shows the reason on screen whenever it did not reply."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Auto-reply and AI fixes",
                "Cloud replies now use valid Gemini model names with automatic fallback, which fixes replies that were silently failing. Clearer errors are shown for a wrong or blocked API key."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Custom illustrated HOPE app logo",
                "The app launcher icon now uses the supplied HOPE artwork across Android's standard, round, and adaptive icon variants. The adaptive version keeps the complete logo inside Android's safe area so launcher masks do not cut off the portrait or wordmark."
            ),
            FeatureNotice(
                "HOPE 2.0",
                "Security clean-up",
                "A pinned release-signature check, removal of an unused permission, and removal of leftover unused resources."
            ),
            FeatureNotice(
                "HOPE AI",
                "Natural voice conversations and single wake greeting",
                "A wake activation now has one guarded greeting request. If speech is already underway, HOPE answers that request rather than injecting a second greeting. Transcript callbacks that repeat cumulative partial/final text are merged, reconnects do not replay the greeting, and live Gemini audio remains on the existing single playback path. Conversation instructions now favor concise, context-aware responses in the configured language."
            ),
            FeatureNotice(
                "HOPE AI",
                "Explicit location privacy and one-shot diagnostics",
                "Enable location awareness separately from sharing approximate locality with Gemini. Approximate or precise permission is requested through Android; HOPE obtains a one-shot fix only when requested, keeps coordinates in a local diagnostic cache, labels stale snapshots, and does not track continuously in the background. Reverse geocoding and sending coordinates to Open-Meteo for live weather each require separate opt-ins. Location is not attached to WhatsApp replies. Notification auto-replies continue to require an authorized rule and an Android-provided inline reply action; an action submission does not confirm delivery, and Android may delay or stop background work."
            ),
            FeatureNotice(
                "HOPE AI",
                "Eight voice-linked expert personas",
                "Each Gemini Live voice currently supported by the app has a distinct instruction profile. Optional automatic expertise routing can adjust the problem-solving approach for a request while preserving the selected audio voice. Customize a voice persona's name, expertise, and tone in AI Persona settings. Capability guidance distinguishes integrated features from those requiring runtime permissions and unavailable services; web research, calendar access, and a separate Gemini voice preview are not currently integrated."
            ),
            FeatureNotice(
                "HOPE AI",
                "Critical thinking and honest responses",
                "Voice and text responses now share core guidance to assess claims independently, correct important errors respectfully, distinguish facts from assumptions, and state uncertainty without exposing chain-of-thought. Spoken answers stay concise while text can provide fuller reasoning and practical trade-offs. Enable or disable the behavior in Settings; changes apply to new chat replies and newly configured voice sessions. Tool permissions and action verification remain governed by their existing systems."
            ),
            FeatureNotice(
                "HOPE 1.9.6",
                "Multi-speaker profiles and personalized addressing",
                "Enroll consenting speakers with three on-device Vosk/Kaldi x-vector samples. Voice embeddings and profile details are AES-GCM encrypted with an Android Keystore key; raw recordings are discarded. Profile changes require Android device authentication, and matching can return UNKNOWN when confidence is low or profiles are ambiguous. Recognition is for personalization only, never authorization. When explicitly enabled, the selected form of address and language are sent as context to the configured AI service; HOPE's existing generated voice is unchanged. Replay resistance and recognition accuracy have not been validated on target devices."
            ),
            FeatureNotice(
                "HOPE 1.9.5",
                "HOPE-only Security Manager",
                "Protect app screens with a separate PIN, 3×3 pattern, or alphanumeric password. Credentials use a salted PBKDF2-HMAC-SHA256 verifier and a Keystore-backed AES-GCM encrypted record. Set a background timeout, enable Android BiometricPrompt, change or disable the app credential after verification, or recover through Android system authentication. “Unlock my phone” asks Android to show its own keyguard flow; HOPE never reads or enters the phone credential. The app lock is separate from Android's device lock."
            ),
            FeatureNotice(
                "HOPE 1.9.4",
                "Voice clipboard and local Hey HOPE standby",
                "Voice clipboard is separately opt-in and supports explicit copy-selected-text, copy-link, paste, and select-all commands through Android Accessibility. HOPE does not save clipboard history, blocks common sensitive-data patterns, and reports paste as unverified when Android or the target app cannot confirm it. Hey HOPE standby is also opt-in and uses the bundled Vosk recognizer locally; only the exact wake phrase with the configured confidence can start the existing conversation service. A foreground notification discloses listening, and Stop listening is always available. Background listening can still be restricted by Android or device firmware."
            ),
            FeatureNotice(
                "HOPE 1.9.3",
                "Redesigned incoming and active call screens",
                "Incoming calls now place the HOPE ORB above caller details with clear Answer and Decline controls. Outgoing and active screens keep the ORB centered and show Android Telecom call states with a bottom-pinned control area. Mute and supported Speaker controls are shown while an outgoing call connects; keypad tones become available when Telecom reports the call active. Connection status is never simulated."
            ),
            FeatureNotice(
                "HOPE 1.9.2",
                "New HOPE Phone call ORB",
                "Call screens now use the supplied particle-style HOPE ORB, rendered with the Three.js library bundled in the app for offline use. Its status follows Android Telecom call states and actual voice recognition; cellular call audio is not captured."
            ),
            FeatureNotice(
                "HOPE 1.9.1",
                "Voice command takes a screenshot",
                "Ask HOPE to take a screenshot or say “screenshot le lo”. On Android 11 and newer, enabled HOPE Accessibility captures and saves it to Pictures/HOPE. On Android 10 and earlier, start Android-approved Screen Share first. The screenshot stays on-device and is not sent to Gemini."
            ),
            FeatureNotice(
                "HOPE 1.9.0",
                "Real-time notification inbox and safer Auto-Reply",
                "HOPE now captures actual Android-delivered notifications for apps you monitor, updates inbox entries by Android notification key, tracks read and dismissed state, and applies configurable local retention. Announcements use the shared HOPE speech queue with per-app, quiet-hour, sensitive-content, headphones, and pause controls. Inbox reply previews can be confirmed and spoken without sending. You can ask HOPE by text or voice to start, stop, or check WhatsApp auto-reply; starting checks required access, monitoring, an authorized rule, and AI configuration. Automatic sending remains off by default and requires an Android-provided inline reply action; action submission does not confirm recipient delivery."
            ),
            FeatureNotice(
                "HOPE 1.8.9",
                "Call screen refreshed with shader ORB",
                "Incoming, outgoing, active, held, and disconnected screens now place the HOPE ORB centrally and animate it from the real Android Telecom call state. Call actions remain connected to Telecom."
            ),
            FeatureNotice(
                "Automatic replies",
                "MessagingStyle notifications supported",
                "Auto-reply now reads the latest incoming message and sender from Android MessagingStyle when a notification omits its normal text preview. Reading notification history is separate from the auto-reply controls; unsupported quick-reply actions and safety exclusions still prevent sending."
            ),
            FeatureNotice(
                "HOPE 1.8.8",
                "Default Assistant starts voice",
                "The Android assistant gesture now launches HOPE Home and starts a live voice session when microphone, notification, battery, and Gemini setup requirements are satisfied."
            ),
            FeatureNotice(
                "Automatic replies",
                "Explicit app and conversation rules",
                "Automatic sending requires explicit app selection and a matching AUTHORIZED_AUTO_REPLY rule. SUGGEST_REPLY rules remain draft-only; sensitive messages and notifications without a supported quick-reply action are not sent."
            ),
            FeatureNotice(
                "Call and notification audio",
                "One shared HOPE announcement voice",
                "Incoming-call and notification announcements now use the same installed Android speech engine and language setting. Gemini Live persona voices remain for live conversations; announcement text stays on-device."
            ),
            FeatureNotice("Agent tasks", "Verified actions and recovery", "HOPE observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear."),
            FeatureNotice("Floating avatar", "Always-on or working-only display", "Choose an avatar, double-tap for compact task progress, drag it anywhere on screen, and choose a dock edge in Settings."),
            FeatureNotice("Task progress", "Four-step compact status", "The task HUD shows the goal, recent steps, a running indicator, completed checks, and a subdued dark progress line."),
            FeatureNotice("Silent execution", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible."),
            FeatureNotice("WhatsApp", "Saved contact lookup", "Contact aliases are supported. Grant Contacts permission first; HOPE will report a missing permission separately from a name mismatch."),
            FeatureNotice("Phone calls", "Permission-aware calling", "Normal calls require Contacts and Phone permissions. WhatsApp calls use WhatsApp and do not place a cellular call."),
            FeatureNotice("Google Flow / AI Studio", "Browser input diagnostics", "The captured Flow run could not verify the prompt input in Chrome. Some editor fields are not exposed to Accessibility; screen sharing must be active and have a fresh frame for visual targeting. The captured logs did not show a Google AI Studio page, so that failure still needs a fresh log capture.", "DIAGNOSTIC"),
            FeatureNotice("Instagram", "Bulk action safety", "Bulk tasks track list progress and require end-of-list evidence. The captured run repeatedly reached the action limit and did not establish completion; avoid treating an unverified result as done.", "DIAGNOSTIC"),
            FeatureNotice("Spotify", "Song search and playback checks", "Searches can inspect results and verify playback before reporting success; physical-device behavior still depends on the Spotify screen and account."),
            FeatureNotice("Permissions", "Per-feature access status", "The System Authorizations page lists microphone, location, notifications, overlay, Accessibility, contacts, phone, camera, Bluetooth, battery, and brightness access."),
            FeatureNotice("AI persona", "Operator profile preview", "Preview your selected assistant voice and the name it will use before activating the persona."),
            FeatureNotice("Appearance", "Light theme by default", "Fresh installs start with the Light theme; you can still select other themes in Settings.")
        )
        val list = binding.updatesList
        list.removeAllViews()
        features.forEach { feature ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), com.jarvis.assistant.R.drawable.bg_jarvis_card)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                }
            }
            card.addView(TextView(requireContext()).apply {
                text = feature.feature.uppercase() + if (feature.kind == "DIAGNOSTIC") "  ·  DIAGNOSTIC" else ""
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
                setPadding(0, dp(8), 0, dp(5))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.details
                textSize = 13f
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            list.addView(card)
        }
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
