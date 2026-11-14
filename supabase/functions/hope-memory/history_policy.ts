export type HistorySourceMetadata = {
  sourceCreatedAt: string;
  sourceTimestampOrigin: TimestampOrigin;
  sourceUserCreatedAt: string;
  sourceUserTimestampOrigin: TimestampOrigin;
  sourceAssistantCreatedAt: string;
  sourceAssistantTimestampOrigin: TimestampOrigin;
  sourceConversationId: string | null;
  sourceTurnId: string | null;
  sourceUserMessageId: string | null;
  sourceAssistantMessageId: string | null;
  sourceTurnOrder: number;
  sourceConversationTitle: string | null;
};

type TimestampOrigin = "client_recorded" | "server_ingested" | "imported_unverified";

type ParsedTimestamp = {
  timestamp: string;
  origin: TimestampOrigin;
};

function parseTimestamp(
  value: unknown,
  originValue: unknown,
  now: Date,
): ParsedTimestamp | null {
  const declaredOrigin = originValue === undefined || originValue === null
    ? null
    : originValue;
  if (
    declaredOrigin !== null &&
    declaredOrigin !== "client_recorded" &&
    declaredOrigin !== "server_ingested" &&
    declaredOrigin !== "imported_unverified"
  ) {
    return null;
  }
  if (value === undefined || value === null) {
    return { timestamp: now.toISOString(), origin: "server_ingested" };
  }
  if (typeof value !== "string" || value.length > 64) return null;
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed) || parsed > now.getTime() + 24 * 60 * 60 * 1000) {
    return null;
  }
  return {
    timestamp: new Date(parsed).toISOString(),
    origin: declaredOrigin ?? "client_recorded",
  };
}

function parseOptionalId(value: unknown, maximumLength: number): string | null | false {
  if (value === undefined || value === null) return null;
  if (
    typeof value !== "string" || value.trim().length < 1 ||
    value.trim().length > maximumLength ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    return false;
  }
  return value.trim();
}

export function parseHistorySourceMetadata(
  value: Record<string, unknown>,
  now: Date = new Date(),
): HistorySourceMetadata | null {
  const baseTimestamp = parseTimestamp(
    value.sourceCreatedAt ?? value.sourceUserCreatedAt ??
      value.sourceAssistantCreatedAt,
    value.sourceTimestampOrigin,
    now,
  );
  const userTimestamp = parseTimestamp(
    value.sourceUserCreatedAt ?? value.sourceCreatedAt,
    value.sourceUserTimestampOrigin ?? value.sourceTimestampOrigin,
    now,
  );
  const assistantTimestamp = parseTimestamp(
    value.sourceAssistantCreatedAt ?? value.sourceCreatedAt,
    value.sourceAssistantTimestampOrigin ?? value.sourceTimestampOrigin,
    now,
  );
  const sourceConversationId = parseOptionalId(value.sourceConversationId, 160);
  const sourceTurnId = parseOptionalId(value.sourceTurnId, 200);
  const sourceUserMessageId = parseOptionalId(value.sourceUserMessageId, 200);
  const sourceAssistantMessageId = parseOptionalId(
    value.sourceAssistantMessageId,
    200,
  );
  const sourceConversationTitle = parseOptionalId(
    value.sourceConversationTitle,
    500,
  );
  const sourceTurnOrder = value.sourceTurnOrder ?? 0;
  if (
    !baseTimestamp || !userTimestamp || !assistantTimestamp ||
    sourceConversationId === false || sourceTurnId === false ||
    sourceUserMessageId === false || sourceAssistantMessageId === false ||
    sourceConversationTitle === false ||
    typeof sourceTurnOrder !== "number" || !Number.isInteger(sourceTurnOrder) ||
    sourceTurnOrder < 0 ||
    sourceTurnOrder > 1_000_000_000
  ) {
    return null;
  }

  return {
    sourceCreatedAt: baseTimestamp.timestamp,
    sourceTimestampOrigin: baseTimestamp.origin,
    sourceUserCreatedAt: userTimestamp.timestamp,
    sourceUserTimestampOrigin: userTimestamp.origin,
    sourceAssistantCreatedAt: assistantTimestamp.timestamp,
    sourceAssistantTimestampOrigin: assistantTimestamp.origin,
    sourceConversationId,
    sourceTurnId,
    sourceUserMessageId,
    sourceAssistantMessageId,
    sourceTurnOrder,
    sourceConversationTitle,
  };
}
