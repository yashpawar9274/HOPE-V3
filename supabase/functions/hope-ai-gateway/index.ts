import { corsHeaders, jsonResponse, readJsonObject, verifiedUser } from "../_shared/auth.ts";

const deno = (globalThis as typeof globalThis & {
  Deno: {
    serve: (handler: (request: Request) => Promise<Response>) => void;
    env: { get: (name: string) => string | undefined };
  };
}).Deno;

deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return jsonResponse({ error: "Method not allowed." }, 405);
  }

  try {
    const user = await verifiedUser(request);
    if (!user) return jsonResponse({ error: "Authentication required." }, 401);

    const payload = await readJsonObject(request);
    if (!payload) return jsonResponse({ error: "Invalid JSON request." }, 400);
    const prompt = payload.prompt;
    const systemInstruction = payload.systemInstruction;
    if (typeof prompt !== "string" || prompt.trim().length === 0 || prompt.length > 30_000) {
      return jsonResponse({ error: "Prompt must contain 1 to 30,000 characters." }, 400);
    }
    if (systemInstruction !== undefined &&
      (typeof systemInstruction !== "string" || systemInstruction.length > 4_000)) {
      return jsonResponse({ error: "System instruction must be no longer than 4,000 characters." }, 400);
    }

    const apiKey = deno.env.get("GEMINI_API_KEY");
    const model = deno.env.get("GEMINI_MODEL") ?? "gemini-2.0-flash";
    if (!apiKey) return jsonResponse({ error: "AI gateway is not configured." }, 503);
    if (!/^[a-zA-Z0-9._-]{1,100}$/.test(model)) {
      return jsonResponse({ error: "AI gateway model configuration is invalid." }, 503);
    }

    const geminiResponse = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": apiKey,
        },
        body: JSON.stringify({
          ...(typeof systemInstruction === "string" && systemInstruction.trim()
            ? { systemInstruction: { parts: [{ text: systemInstruction }] } }
            : {}),
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          generationConfig: { maxOutputTokens: 2048 },
        }),
      },
    );
    if (!geminiResponse.ok) {
      return jsonResponse({ error: "AI provider request failed." }, 502);
    }
    const result = await geminiResponse.json();
    const text = result?.candidates?.[0]?.content?.parts
      ?.map((part: { text?: unknown }) => typeof part.text === "string" ? part.text : "")
      .join("")
      .trim();
    if (!text) return jsonResponse({ error: "AI provider returned no response." }, 502);

    return jsonResponse({ response: text });
  } catch {
    return jsonResponse({ error: "The AI gateway could not complete this request." }, 500);
  }
});
