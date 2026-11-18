# Social Intelligence for Smart Auto Reply

`SocialIntelligenceEngine` performs deterministic checks before the notification quick-reply action. It identifies explicit identity questions, career and meeting requests, stop-contact boundaries, answered repeats, conversation endings, and uninspectable media. The model may suggest wording for ordinary messages, but it does not decide whether a high-impact response is authorized. Shared communication guidance also enters text, Gemini Live, WhatsApp, and background-agent prompts through `CoreBehaviorEngine`; deterministic send/silence/escalation decisions are currently enforced only in Smart Auto Reply.

## Safety and response behavior

- HOPE does not introduce itself on first contact. It identifies itself as HOPE, Yash's AI assistant only when the sender asks who they are speaking with.
- Career, meeting, sensitive, and owner-decision requests are escalated before any holding response. The private Android notification includes the sender, original message, recent available context, a summary, missing information, and a suggested response.
- Career facts and availability are never inferred from chat context. Sensitive content is not sent to the model for drafting and is never auto-replied to.
- A holding response is sent only after the private escalation notification was accepted by Android; if notification permission or posting fails, HOPE does not claim that Yash was informed.
- A contact asking HOPE to stop unnecessary communication receives at most one concise acknowledgement. The conversation boundary is persisted locally and irrelevant future messages are ignored; a clearly relevant opportunity or urgent request can reopen processing.
- Thanks, goodbyes, answered repeated questions, and messages without accessible text are not answered when conversation-ending controls apply.

## Settings and persistence

Settings > Smart Auto Reply stores persona, social-intelligence, professional-communication, emotional-context, conversation-ending, escalation, human-review, and stopped-conversation preferences locally. Up to 12 submitted HOPE replies per conversation are retained locally for 30 days to identify answered repeats; stop-contact boundaries are also device-local and capped at 300 conversations. When human review is enabled, career and meeting questions are privately escalated but not answered; when disabled, HOPE may send only a safe holding response after escalation succeeds. These preferences and replies are not synchronized to Supabase. Users can reset stopped-conversation boundaries and clear conversation context from the same screen.

## Current limits

Intent and emotion detection use conservative text patterns, not a general-purpose classifier; ambiguous or unrecognized language may be classified as ordinary conversation. Contact-priority ranking is not implemented. The escalation notification and execution log are not yet a persistent, user-actionable Pending Decisions queue, and there is no approve-and-send workflow. WhatsApp access remains limited to messages exposed by Android notifications and supported notification reply actions. Gmail and other messaging channels do not yet invoke the same deterministic gates.
