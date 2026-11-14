import { parseHistorySourceMetadata } from "./history_policy.ts";

Deno.test("history source metadata preserves provided timestamps and conversation IDs", () => {
  const metadata = parseHistorySourceMetadata({
    sourceCreatedAt: "2025-03-04T05:06:07.000Z",
    sourceConversationId: "conversation-17",
  });
  if (
    metadata?.sourceCreatedAt !== "2025-03-04T05:06:07.000Z" ||
    metadata.sourceTimestampOrigin !== "client_recorded" ||
    metadata.sourceConversationId !== "conversation-17"
  ) {
    throw new Error("Expected source metadata to be preserved.");
  }
});

Deno.test("missing history timestamps are clearly marked as server-ingested", () => {
  const metadata = parseHistorySourceMetadata(
    {},
    new Date("2026-11-14T00:00:00.000Z"),
  );
  if (
    metadata?.sourceCreatedAt !== "2026-11-14T00:00:00.000Z" ||
    metadata.sourceTimestampOrigin !== "server_ingested" ||
    metadata.sourceConversationId !== null
  ) {
    throw new Error(
      "Expected missing source metadata to use an explicit ingestion timestamp.",
    );
  }
});

Deno.test("preserves individual message timestamps and original source references", () => {
  const metadata = parseHistorySourceMetadata({
    sourceCreatedAt: "2025-03-04T05:06:07.000Z",
    sourceUserCreatedAt: "2025-03-04T05:06:06.000Z",
    sourceAssistantCreatedAt: "2025-03-04T05:06:07.000Z",
    sourceConversationId: "conversation-17",
    sourceTurnId: "turn-8",
    sourceUserMessageId: "message-15",
    sourceAssistantMessageId: "message-16",
    sourceTurnOrder: 8,
    sourceConversationTitle: "Project discussion",
  });
  if (
    metadata?.sourceUserCreatedAt !== "2025-03-04T05:06:06.000Z" ||
    metadata.sourceAssistantCreatedAt !== "2025-03-04T05:06:07.000Z" ||
    metadata.sourceUserMessageId !== "message-15" ||
    metadata.sourceAssistantMessageId !== "message-16" ||
    metadata.sourceTurnOrder !== 8 ||
    metadata.sourceConversationTitle !== "Project discussion"
  ) {
    throw new Error("Expected source message ordering and references to be preserved.");
  }
});

Deno.test("preserves imported timestamp provenance per message", () => {
  const metadata = parseHistorySourceMetadata({
    sourceCreatedAt: "2020-03-04T05:06:07.000Z",
    sourceUserCreatedAt: "2020-03-04T05:06:06.000Z",
    sourceAssistantCreatedAt: "2020-03-04T05:06:07.000Z",
    sourceTimestampOrigin: "server_ingested",
    sourceUserTimestampOrigin: "imported_unverified",
    sourceAssistantTimestampOrigin: "client_recorded",
  });
  if (
    metadata?.sourceTimestampOrigin !== "server_ingested" ||
    metadata.sourceUserTimestampOrigin !== "imported_unverified" ||
    metadata.sourceAssistantTimestampOrigin !== "client_recorded"
  ) {
    throw new Error("Expected timestamp provenance to be preserved per message.");
  }
  if (parseHistorySourceMetadata({
    sourceCreatedAt: "2020-03-04T05:06:07.000Z",
    sourceTimestampOrigin: "fabricated",
  })) {
    throw new Error("Expected unsupported timestamp provenance to be rejected.");
  }
  if (parseHistorySourceMetadata({ sourceTimestampOrigin: "fabricated" })) {
    throw new Error("Expected unsupported provenance to be rejected without a source timestamp.");
  }
});

Deno.test("rejects malformed or implausibly future history metadata", () => {
  const now = new Date("2026-11-14T00:00:00.000Z");
  if (parseHistorySourceMetadata({ sourceCreatedAt: "not-a-date" }, now)) {
    throw new Error("Expected invalid timestamps to be rejected.");
  }
  if (
    parseHistorySourceMetadata(
      { sourceCreatedAt: "2026-11-16T00:00:00.000Z" },
      now,
    )
  ) {
    throw new Error("Expected a far-future timestamp to be rejected.");
  }
  if (
    parseHistorySourceMetadata({ sourceConversationId: "invalid\u0000id" }, now)
  ) {
    throw new Error(
      "Expected control characters in conversation IDs to be rejected.",
    );
  }
  if (parseHistorySourceMetadata({ sourceTurnOrder: -1 }, now)) {
    throw new Error("Expected invalid source turn ordering to be rejected.");
  }
});
