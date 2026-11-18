# Implementation Changelog

## 2026-11-15 — Memory reliability checkpoint

- Removed automatic promotion of frequently played songs into confirmed favorites.
- Marked previously persisted favorite values unconfirmed by default; only explicit favorite statements can confirm them.
- Restricted favorite extraction so ordinary song love or app usage is not upgraded into a favorite.
- Removed fabricated favorite-song and favorite-app defaults from recall and prompt context.
- Made unknown favorite-song playback requests ask the user instead of choosing a personal fallback.
- Changed explicit memory saves to persist locally before the cloud request and report expected cloud failures.
- Made forget responses distinguish local deletion from unconfirmed cloud deletion.
- Reworded empty/summary memory responses so they do not promise permanent recall or present stored entries as verified facts.
- Removed the Room memory database's destructive-migration fallback.
- Added focused unit tests for known and unknown favorite-song responses.

### Remaining work

This checkpoint does not implement the local cloud-sync outbox, retryable deletion, local confidence/provenance metadata, or the full repository audit. Cloud services and device-dependent behavior remain unverified.
