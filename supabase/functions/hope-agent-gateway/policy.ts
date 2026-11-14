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

export function isStructuredToolResult(
  value: unknown,
  request: {
    taskId: string;
    sessionId: string;
    toolCallId: string;
    toolName: string;
    parameters: Record<string, unknown>;
    resultStatus: string;
  },
): boolean {
  if (!isPlainObject(value) ||
    value.schemaVersion !== 1 ||
    value.taskId !== request.taskId ||
    value.sessionId !== request.sessionId ||
    value.toolCallId !== request.toolCallId ||
    value.toolName !== request.toolName ||
    value.resultStatus !== request.resultStatus ||
    typeof value.verified !== "boolean") {
    return false;
  }

  if (request.resultStatus !== "SUCCESS") {
    return value.verified === false && typeof value.message === "string";
  }
  if (!value.verified) return false;

  if (request.toolName === "get_device_context") {
    return Number.isInteger(value.batteryPercent) &&
      Number(value.batteryPercent) >= 0 && Number(value.batteryPercent) <= 100 &&
      (typeof value.isCharging === "boolean" || value.isCharging === null) &&
      typeof value.batterySaverEnabled === "boolean" &&
      Number.isInteger(value.brightnessPercent) &&
      Number(value.brightnessPercent) >= 0 && Number(value.brightnessPercent) <= 100 &&
      typeof value.networkValidated === "boolean" &&
      ["wifi", "cellular", "ethernet", "vpn", "unknown"].includes(String(value.networkTransport));
  }

  if (request.toolName === "set_screen_brightness") {
    const requestedPercent = request.parameters.percent;
    return Number.isInteger(value.requestedPercent) &&
      value.requestedPercent === requestedPercent &&
      Number.isInteger(value.actualPercent) &&
      Number(value.actualPercent) >= 0 && Number(value.actualPercent) <= 100 &&
      typeof value.message === "string";
  }

  return false;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
