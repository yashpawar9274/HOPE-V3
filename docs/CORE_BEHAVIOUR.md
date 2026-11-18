# Core Behaviour Instructions

Core Behaviour stores explicit, persistent owner instructions separately from conversational memories. Rules are stored in Room first, then verified by reading the saved row. If Cloud Long-Term Memory is enabled for the signed-in account, the existing authenticated `hope-memory` outbox syncs rules and durable deletion tombstones to Supabase; cloud changes are pulled into the local cache when the app starts and when this screen opens.

The recognizer intentionally accepts clear permanence language such as “never”, “always”, “from now on”, “remember permanently”, and repeat-correction phrases. Vague instructions such as “never do that again” are not stored; HOPE asks what specific behavior should change. One-time requests are not promoted into permanent rules. Duplicate rules are left unchanged, and contradictory active rules are not overwritten.

Active rules and shared social communication guidance are supplied to text chat, Gemini Live voice, WhatsApp auto-reply, and Cloud Agent task prompts. Conflicting rules pause WhatsApp auto-reply and Cloud Agent task creation; text chat asks the owner to resolve the conflict. The management screen is under Settings → Memory & Intelligence → Core Behaviour and includes search, edit, disable/reactivate, explicit deletion, scope, and local history.

WhatsApp Smart Auto Reply also runs the deterministic social-intelligence gates documented in [SOCIAL_INTELLIGENCE.md](./SOCIAL_INTELLIGENCE.md) before sending. Those action gates do not yet run in Gmail or other messaging channels; the shared guidance in other prompt paths is not a substitute for deterministic action enforcement.

Cloud sync uses the existing Cloud Long-Term Memory opt-in and Firebase account ownership. A queued sync is not reported as a completed cloud write. The Memory settings sync status reports pending and failed outbox jobs. Turning Cloud Memory off leaves local rules available and pauses cloud sync.

Natural-language rules cannot be converted into a complete deterministic policy for every possible action. They are shared as high-priority instructions and conflicts are guarded where detectable, but they do not replace Android permissions, existing action confirmations, authentication, or platform restrictions. Arbitrary user rules must not be treated as a security boundary or used to enable unrestricted autonomy.
