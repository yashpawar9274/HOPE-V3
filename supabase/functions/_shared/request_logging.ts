export async function withStructuredRequestLogging(
  request: Request,
  route: string,
  handler: () => Promise<Response>,
): Promise<Response> {
  const requestId = crypto.randomUUID();
  const startedAt = Date.now();
  let response: Response;

  try {
    response = await handler();
  } catch (error) {
    console.error(JSON.stringify({
      event: "backend_request_unhandled_error",
      route,
      request_id: requestId,
      method: request.method,
      error_type: error instanceof Error ? error.name : "UnknownError",
    }));
    response = new Response(
      JSON.stringify({ error: "The backend request could not be completed." }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }

  const headers = new Headers(response.headers);
  headers.set("x-request-id", requestId);
  console.log(JSON.stringify({
    event: "backend_request_completed",
    route,
    request_id: requestId,
    method: request.method,
    status_code: response.status,
    duration_ms: Math.max(0, Date.now() - startedAt),
  }));
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
