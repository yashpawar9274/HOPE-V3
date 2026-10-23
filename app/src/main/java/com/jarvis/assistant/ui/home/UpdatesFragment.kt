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
            FeatureNotice("Agent tasks", "Verified actions and recovery", "JARVIS observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear."),
            FeatureNotice("Floating avatar", "Always-on or working-only display", "Choose an avatar, double-tap for compact task progress, drag it anywhere on screen, and choose a dock edge in Settings."),
            FeatureNotice("Task progress", "Four-step compact status", "The task HUD shows the goal, recent steps, a running indicator, completed checks, and a subdued dark progress line."),
            FeatureNotice("Silent execution", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible."),
            FeatureNotice("WhatsApp", "Saved contact lookup", "Contact aliases are supported. Grant Contacts permission first; JARVIS will report a missing permission separately from a name mismatch."),
            FeatureNotice("Phone calls", "Permission-aware calling", "Normal calls require Contacts and Phone permissions. WhatsApp calls use WhatsApp and do not place a cellular call."),
            FeatureNotice("Google Flow / AI Studio", "Browser input diagnostics", "The captured Flow run could not verify the prompt input in Chrome. Some editor fields are not exposed to Accessibility; screen sharing must be active and have a fresh frame for visual targeting. The captured logs did not show a Google AI Studio page, so that failure still needs a fresh log capture.", "DIAGNOSTIC"),
            FeatureNotice("Instagram", "Bulk action safety", "Bulk tasks track list progress and require end-of-list evidence. The captured run repeatedly reached the action limit and did not establish completion; avoid treating an unverified result as done.", "DIAGNOSTIC"),
            FeatureNotice("Spotify", "Song search and playback checks", "Searches can inspect results and verify playback before reporting success; physical-device behavior still depends on the Spotify screen and account."),
            FeatureNotice("Permissions", "Per-feature access status", "The System Authorizations page lists microphone, location, notifications, overlay, Accessibility, contacts, phone, camera, Bluetooth, battery, and brightness access."),
            FeatureNotice("AI persona", "Operator profile preview", "Preview your selected assistant voice and the name it will use before activating the persona."),
            FeatureNotice("Appearance", "Sunset theme by default", "Fresh installs start with the sunset theme; you can still select other themes in Settings.")
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
