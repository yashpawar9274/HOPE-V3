import { withStructuredRequestLogging } from "./request_logging.ts";

Deno.test("request logging adds a server-generated correlation ID to responses", async () => {
  const response = await withStructuredRequestLogging(
    new Request("https://hope.invalid/functions/v1/test", { method: "POST" }),
    "test-route",
    async () => new Response("ok", { status: 202 }),
  );
  const requestId = response.headers.get("x-request-id") ?? "";
  if (!/^[0-9a-f-]{36}$/i.test(requestId)) {
    throw new Error("Expected a server-generated request ID.");
  }
  if (response.status !== 202 || await response.text() !== "ok") {
    throw new Error("Expected the original response to be preserved.");
  }
});

Deno.test("request logging returns a safe error response for unhandled failures", async () => {
  const response = await withStructuredRequestLogging(
    new Request("https://hope.invalid/functions/v1/test"),
    "test-route",
    async () => {
      throw new Error("private provider error");
    },
  );
  const body = await response.text();
  if (response.status !== 500 || body.includes("private provider error")) {
    throw new Error("Unhandled errors must not leak exception details.");
  }
  if (!response.headers.get("x-request-id")) {
    throw new Error("Expected an error response to include a request ID.");
  }
});
