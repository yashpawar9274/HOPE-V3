import {
  isAgentUuid,
  isStructuredToolResult,
  isToolResultStatus,
  parseEventPage,
  stableJson,
} from "./policy.ts";

Deno.test("accepts generated UUIDs and rejects malformed identifiers", () => {
  if (!isAgentUuid("8db1f13b-2781-4d54-b80c-b13b9a2c3b10")) {
    throw new Error("Expected a valid UUID to be accepted.");
  }
  if (isAgentUuid("user-provided-id")) {
    throw new Error("Expected a malformed ID to be rejected.");
  }
});

Deno.test("event cursors default safely and enforce bounded pages", () => {
  const defaults = parseEventPage(undefined, undefined);
  if (defaults?.afterSequence !== 0 || defaults.limit !== 50) {
    throw new Error("Expected default event page values.");
  }
  if (!parseEventPage(10, 100)) throw new Error("Expected valid bounds to pass.");
  for (const [cursor, limit] of [[-1, 10], [1.5, 10], [0, 101], ["1", 10]]) {
    if (parseEventPage(cursor, limit)) {
      throw new Error(`Expected invalid event page ${String(cursor)}/${String(limit)} to fail.`);
    }
  }
});

Deno.test("tool results accept only the defined execution outcomes", () => {
  if (!isToolResultStatus("USER_CONFIRMATION_REQUIRED")) {
    throw new Error("Expected a supported tool result to pass.");
  }
  if (isToolResultStatus("COMPLETED")) {
    throw new Error("Task completion must not be accepted as a tool result.");
  }
});

Deno.test("tool success requires a matching verified device result", () => {
  const request = {
    taskId: "123e4567-e89b-42d3-a456-426614174000",
    sessionId: "123e4567-e89b-42d3-a456-426614174001",
    toolCallId: "123e4567-e89b-42d3-a456-426614174002",
    toolName: "get_device_context",
    parameters: {},
    resultStatus: "SUCCESS",
  };
  const valid = {
    schemaVersion: 1,
    ...request,
    batteryPercent: 42,
    isCharging: false,
    batterySaverEnabled: true,
    brightnessPercent: 35,
    networkValidated: true,
    networkTransport: "wifi",
    verified: true,
  };
  if (!isStructuredToolResult(valid, request)) {
    throw new Error("Expected a complete verified device result to pass.");
  }
  if (isStructuredToolResult({ ...valid, verified: false }, request) ||
    isStructuredToolResult({ ...valid, batteryPercent: 101 }, request) ||
    isStructuredToolResult({ ...valid, taskId: "forged" }, request)) {
    throw new Error("Unverified or malformed device results must be rejected.");
  }
});

Deno.test("idempotent result comparison ignores JSON object key order", () => {
  const first = stableJson({ result: { status: "ok", count: 2 } });
  const second = stableJson({ result: { count: 2, status: "ok" } });
  if (first !== second) throw new Error("Expected canonical JSON output.");
});
