# Memory Architecture

## Current flow

- `MemoryManager` coordinates explicit memory requests, short-term conversation context, local facts, and optional Cloud Memory calls.
- `MemoryRepository` persists local records through `MemoryDao` in the Room `MemoryDatabase`.
- `CloudMemoryRepository` sends authenticated requests to the Supabase `hope-memory` Edge Function when cloud memory is enabled for the signed-in owner.
- Conversation turns are persisted separately through `ChatRepository`.

## Changes in this checkpoint

- Explicit `remember` requests write to Room before attempting an optional cloud write. An expected cloud-memory failure is reported as a cloud-sync failure while retaining the local fact.
- Favorite recall uses only an explicitly stored favorite; play counts are not treated as confirmation. Empty favorite values are represented as unknown.
- Legacy favorite values are retained but are not surfaced unless the user explicitly confirms them; extraction now requires an explicit favorite statement.
- A local forget operation reports only local deletion; it does not claim cloud deletion.
- The memory database no longer opts into destructive migration fallback.

## Known gaps

- There is no local outbox for retrying failed cloud writes or deletes after network restoration.
- Local facts do not consistently record confidence, verification status, or a source conversation/message reference.
- Cloud deletion and local deletion are not yet coordinated into a reliable offline-capable operation.
- No schema migration was added; future schema changes must use explicit non-destructive Room migrations.
- Cloud behavior requires deployed Supabase Edge Functions, Firebase authentication, and configured server-side secrets; none was runtime-verified here.
