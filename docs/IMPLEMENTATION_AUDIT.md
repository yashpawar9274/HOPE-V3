# HOPE-V3 Implementation Audit

Date: 2026-11-15

## Scope and evidence

This is an incremental checkpoint audit, not a complete release audit. The memory code paths below were inspected directly. The broader feature inventory in [PHASE_0_AUDIT.md](./PHASE_0_AUDIT.md) is a prior source-level review; its runtime claims have not been independently re-verified in this pass.

Status definitions: **WORKING** means verified by an appropriate test; **PARTIAL** means code exists but behavior is incomplete; **BROKEN** means a reproducible defect was found; **NOT CONFIGURED** means an external dependency is required; **NOT VERIFIED** means available evidence is insufficient.

## Memory findings

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Local memory storage | PARTIAL | Room-backed records exist. `rememberFact()` now stores locally before attempting cloud save. Duplicate suppression and a durable local cloud outbox are not implemented. |
| Unknown favorite handling | BROKEN before this change; fixed in this pass | Favorite songs/apps previously defaulted to specific values, and play counts/usages were promoted to favorites. Existing legacy preference values default to unconfirmed and are preserved but not surfaced as facts until explicitly restated. Focused policy tests were added. |
| Memory deletion | PARTIAL | Local keyword deletion exists and responses no longer claim cloud deletion. Cloud structured-fact/Mem0 deletion propagation and offline deletion queue behavior are not implemented here. |
| Cloud Memory | NOT VERIFIED | Authenticated Edge Function calls and server-side extraction/status APIs exist in source. No live backend or credentials were used. Local saves survive a reported `CloudMemoryException`, but a retryable local outbox is still required. |
| Memory confidence/provenance | PARTIAL | Cloud search formats some provenance and verification metadata. Local `MemoryEntity` has no confidence/verification/source fields, and inferred vs user-confirmed local facts are not consistently represented. |
| Conversation persistence / historical recall | PARTIAL | Local chat history and cloud history search/backup code exist. Full durable persistence, retention behavior, and retrieval quality were not tested against a deployed backend. |
| Room migration safety | BROKEN before this change; mitigated in this pass | The memory database used `fallbackToDestructiveMigration()`. That destructive fallback was removed. There are no schema changes or migration tests in this change. |

## Validation boundary and blockers

- Unit tests and Android build results are reported with the implementation change; they do not prove cloud deployment or device behavior.
- No Firebase credentials, Supabase deployment, Mem0 service, or Android device was used.
- Cloud sync retry/outbox, deletion propagation, structured correction, import/export against a live backend, semantic recall, and dashboard action coverage remain unverified.
- The complete audit of voice, permissions, settings, automation, security, and other application features has not been performed in this pass.

This checkpoint does **not** establish release readiness.

## AI routing checkpoint (2026-11-18)

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Shared text routing | ONLINE ONLY | `AiRouter` sends text generation to Gemini. Gemma inference, its Settings screen, and Offline/Automatic routing were removed in 3.1.5. Old mode preferences are cleared on app startup. |
| Gemini Live voice | ONLINE ONLY | Live voice uses Gemini and requires a configured API key, network access, and Android permissions. The old local-only mode no longer blocks voice with `OFFLINE VOICE UNAVAILABLE`. |
| Local wake-word detection | RETAINED | Vosk remains a separate on-device wake-word detector; it does not provide offline conversations or local AI inference. |
| Existing Gemma model files | RETAINED, UNUSED | Previously downloaded files are left in app-private storage and are no longer loaded. They are not deleted automatically. |
| Native llama.cpp / GGUF | NOT IMPLEMENTED | No llama.cpp/JNI or CMake runtime and no GGUF model manager are present; the previous LiteRT Gemma runtime was removed. |
| Ollama provider | NOT IMPLEMENTED | There is no Ollama provider or endpoint configuration in the Android app. |
| Offline conversation and image models | NOT IMPLEMENTED | The removed local text model did not support conversational voice or image inference. |
| Cloud Memory | SEPARATE OPT-IN | Cloud Memory remains independently consented and may sync/retrieve when enabled. |
| Device performance / release | NOT VERIFIED | No physical device, thermal/performance benchmark, offline lifecycle run, release signing credentials, or production install was available in this pass. |

This checkpoint documents the online-only routing change; it is not completion of a local AI migration or proof of device-level behavior.
