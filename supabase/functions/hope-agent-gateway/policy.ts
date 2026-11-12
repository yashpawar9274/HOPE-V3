const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const TOOL_RESULT_STATES = new Set([
  "SUCCESS",
  "FAILED",
  "PERMISSION_REQUIRED",
  "USER_CONFIRMATION_REQUIRED",
  "UNSUPPORTED",
  "TIMEOUT",
  "CANCELLED",
]);

export function isAgentUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function parseEventPage(
  afterSequence: unknown,
  limit: unknown,
): { afterSequence: number; limit: number } | null {
  const cursor = afterSequence === undefined ? 0 : afterSequence;
  const pageSize = limit === undefined ? 50 : limit;
  if (typeof cursor !== "number" || !Number.isSafeInteger(cursor) || cursor < 0 ||
    typeof pageSize !== "number" || !Number.isSafeInteger(pageSize) ||
    pageSize < 1 || pageSize > 100) {
    return null;
  }
  return { afterSequence: cursor, limit: pageSize };
}

export function isToolResultStatus(value: unknown): value is string {
  return typeof value === "string" && TOOL_RESULT_STATES.has(value);
}

export function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (typeof value === "object" && value !== null) {
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort().map((key) =>
      `${JSON.stringify(key)}:${stableJson(object[key])}`
    ).join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}
