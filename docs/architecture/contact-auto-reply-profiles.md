# Contact-specific notification reply profiles

Contact reply profiles extend the existing notification listener and `RemoteInput`
reply path. They do not log in to messaging accounts or send messages through a
separate API.

## Consent and local storage

Profile discovery is off by default. Enabling **Contact-specific reply profiles**
in Auto-Reply settings records notification identity labels only for supported
apps while notification reading is enabled. Message bodies are not copied into
the profile database. Existing notification history is controlled separately.

Profiles, discovered identities, and compact reply-event metadata live in the
separate `contact_reply_profiles.db` Room database; the existing memories database
and its migration strategy are unchanged. Event records contain a random token,
profile/platform identity, outcome, and timestamp—not message text or a
message-derived hash. Event metadata is pruned after 90 days. The profile screen
can clear all of this local profile data.

Identity resolution uses an app conversation shortcut ID when available, then a
normalized phone address from the notification sender. Phone addresses are
SHA-256 hashed before storage. When neither stable identifier is exposed, the
notification key is hashed and kept as a notification-instance identity. Display
names are labels only and are never used to merge contacts. Consequently,
notifications without stable identifiers may need a new profile for each
notification instance.

## Behavior and fallback

| Profile mode | Behavior |
| --- | --- |
| Disabled | Suppresses the global Smart Auto-Reply fallback for that matched contact. |
| Suggest only | Generates a local HOPE reply suggestion; it never invokes `RemoteInput`. |
| Limited auto-reply | Uses the existing quick-reply action only after the user explicitly confirms this mode. |
| No matching profile | Uses the existing global Smart Auto-Reply setting as the explicitly configured fallback. |

Every mode still observes the notification master switch, per-app enablement,
quiet hours, exclusions, sensitive-content checks, supported-package allowlist,
group-chat and auto-responder checks, and duplicate suppression. Limited
auto-reply additionally requires an unambiguous free-form reply action from the
original notification; if the app does not expose one, HOPE does not send.
Contact-specific tone, language, and bounded owner guidance are included in the
existing reply prompt; safety rules remain authoritative.

AI use remains controlled by existing AI reply and cloud-automatic-use settings.
No copied content is sent to Gemini unless the existing configured AI route and
its consent settings allow that route.

## Device limitations

Notification metadata varies by app and Android version. Some apps expose only
an unstable notification-instance identifier, and group status can be missing
or inaccurate. Profile discovery is limited to the currently supported
notification packages. Device/API behavior of third-party `RemoteInput` actions
cannot be completely covered by local unit tests.
