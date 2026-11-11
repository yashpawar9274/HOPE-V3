import {
  corsHeaders,
  jsonResponse,
  memoryDatabaseClient,
  readJsonObject,
  verifiedUser,
} from "../_shared/auth.ts";

declare const Deno: {
  serve(handler: (request: Request) => Promise<Response>): void;
  env: { get(name: string): string | undefined };
};

const MEM0_BASE_URL = "https://api.mem0.ai";
const TURN_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

Deno.serve(async (request: Request): Promise<Response> => {
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
    const operation = payload.operation;
    const apiKey = Deno.env.get("MEM0_API_KEY");
    if (operation !== "clear" && !apiKey) {
      return jsonResponse({ error: "Long-term memory service is not configured." }, 503);
    }

    if (operation === "save_turn" || operation === "remember") {
      const turnId = payload.turnId;
      const userText = operation === "remember"
        ? payload.content
        : payload.userText;
      const assistantText = operation === "remember"
        ? "Saved as a HOPE long-term memory note."
        : payload.assistantText;
      const source = operation === "remember" ? "manual" : payload.source;
      if (typeof turnId !== "string" || !TURN_ID_PATTERN.test(turnId)) {
        return jsonResponse({ error: "A valid turn ID is required." }, 400);
      }
      if (typeof userText !== "string" || userText.trim().length === 0 ||
        userText.length > 20_000 ||
        typeof assistantText !== "string" || assistantText.length > 20_000) {
        return jsonResponse({ error: "Conversation text must be within the supported size." }, 400);
      }
      if (source !== "chat" && source !== "live_voice" && source !== "manual") {
        return jsonResponse({ error: "Unsupported conversation source." }, 400);
      }

      const database = memoryDatabaseClient(user);
      const { error: saveError } = await database
        .from("hope_conversation_turns")
        .upsert(
          {
            user_id: user.id,
            turn_id: turnId,
            user_text: userText.trim(),
            assistant_text: assistantText.trim(),
            source,
          },
          { onConflict: "user_id,turn_id" },
        );
      if (saveError) return jsonResponse({ error: "Conversation transcript could not be stored." }, 502);

      const mem0Response = await fetch(`${MEM0_BASE_URL}/v3/memories/add/`, {
        method: "POST",
        headers: {
          Authorization: `Token ${apiKey}`,
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          user_id: user.id,
          messages: [
            { role: "user", content: userText.trim() },
            { role: "assistant", content: assistantText.trim() },
          ],
          metadata: { source: "hope_android", turn_id: turnId },
        }),
      });
      if (!mem0Response.ok) {
        return jsonResponse({
          error: "Transcript was stored, but Mem0 could not extract memories. Retry this save.",
          transcriptStored: true,
        }, 502);
      }
      return jsonResponse({ saved: true, memoryExtractionQueued: true }, 202);
    }

    if (operation === "save_turns") {
      if (!Array.isArray(payload.turns) || payload.turns.length < 1 || payload.turns.length > 10) {
        return jsonResponse({ error: "History batches must contain 1 to 10 turns." }, 400);
      }
      const turns: Array<{
        turn_id: string;
        user_text: string;
        assistant_text: string;
        source: "chat";
      }> = [];
      let totalCharacters = 0;
      for (const value of payload.turns) {
        if (typeof value !== "object" || value === null || Array.isArray(value)) {
          return jsonResponse({ error: "A history turn is invalid." }, 400);
        }
        const turn = value as Record<string, unknown>;
        if (typeof turn.turnId !== "string" || !TURN_ID_PATTERN.test(turn.turnId) ||
          typeof turn.userText !== "string" || turn.userText.trim().length === 0 ||
          typeof turn.assistantText !== "string" || turn.assistantText.trim().length === 0 ||
          turn.userText.length > 20_000 || turn.assistantText.length > 20_000) {
          return jsonResponse({ error: "A history turn has invalid or oversized text." }, 400);
        }
        totalCharacters += turn.userText.length + turn.assistantText.length;
        if (totalCharacters > 400_000) {
          return jsonResponse({ error: "History batch exceeds the supported size." }, 400);
        }
        turns.push({
          turn_id: turn.turnId,
          user_text: turn.userText.trim(),
          assistant_text: turn.assistantText.trim(),
          source: "chat",
        });
      }

      const database = memoryDatabaseClient(user);
      const { error: saveError } = await database
        .from("hope_conversation_turns")
        .upsert(
          turns.map((turn) => ({ ...turn, user_id: user.id })),
          { onConflict: "user_id,turn_id" },
        );
      if (saveError) return jsonResponse({ error: "Previous conversation history could not be stored." }, 502);

      const mem0Response = await fetch(`${MEM0_BASE_URL}/v3/memories/add/`, {
        method: "POST",
        headers: {
          Authorization: `Token ${apiKey}`,
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          user_id: user.id,
          messages: turns.flatMap((turn) => [
            { role: "user", content: turn.user_text },
            { role: "assistant", content: turn.assistant_text },
          ]),
          metadata: { source: "hope_history_import" },
        }),
      });
      if (!mem0Response.ok) {
        return jsonResponse({
          error: "History was stored, but Mem0 could not extract memories. Retry this batch.",
          transcriptsStored: true,
        }, 502);
      }
      return jsonResponse({ turnsStored: turns.length, memoryExtractionQueued: true }, 202);
    }

    if (operation === "search") {
      if (typeof payload.query !== "string" || payload.query.trim().length === 0 ||
        payload.query.length > 2_000) {
        return jsonResponse({ error: "Memory query must contain 1 to 2,000 characters." }, 400);
      }
      const database = memoryDatabaseClient(user);
      const [mem0Response, transcriptResult] = await Promise.all([
        fetch(`${MEM0_BASE_URL}/v3/memories/search/`, {
          method: "POST",
          headers: {
            Authorization: `Token ${apiKey}`,
            Accept: "application/json",
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            query: payload.query.trim(),
            filters: { user_id: user.id },
            top_k: 6,
            threshold: 0.1,
          }),
        }),
        database.rpc("search_hope_conversation_turns", {
          search_query: payload.query.trim(),
          max_results: 5,
        }),
      ]);
      if (!mem0Response.ok || transcriptResult.error) {
        return jsonResponse({ error: "Long-term memory search could not be completed." }, 502);
      }
      const mem0Payload: unknown = await mem0Response.json();
      const memories = typeof mem0Payload === "object" && mem0Payload !== null &&
          Array.isArray((mem0Payload as { results?: unknown }).results)
        ? (mem0Payload as { results: Array<Record<string, unknown>> }).results
            .filter((entry) => typeof entry.memory === "string")
            .map((entry) => ({ memory: entry.memory }))
        : [];
      return jsonResponse({
        memories,
        conversations: (transcriptResult.data ?? []).map((turn: {
          user_text: string;
          assistant_text: string;
          created_at: string;
        }) => ({
          userText: turn.user_text,
          assistantText: turn.assistant_text,
          createdAt: turn.created_at,
        })),
      });
    }

    if (operation === "clear") {
      if (!apiKey) return jsonResponse({ error: "Long-term memory service is not configured." }, 503);
      const database = memoryDatabaseClient(user);
      const [mem0Response, transcriptResult] = await Promise.all([
        fetch(`${MEM0_BASE_URL}/v1/memories/?user_id=${encodeURIComponent(user.id)}`, {
          method: "DELETE",
          headers: {
            Authorization: `Token ${apiKey}`,
            Accept: "application/json",
          },
        }),
        database.from("hope_conversation_turns").delete().eq("user_id", user.id),
      ]);
      if (!mem0Response.ok || transcriptResult.error) {
        return jsonResponse({
          error: "Some cloud memories could not be cleared. Retry and verify the memory status.",
          transcriptsDeleted: !transcriptResult.error,
          mem0DeletionQueued: mem0Response.ok,
        }, 502);
      }
      return jsonResponse({ transcriptsDeleted: true, mem0DeletionQueued: true });
    }

    return jsonResponse({ error: "Unsupported memory operation." }, 400);
  } catch {
    return jsonResponse({ error: "The memory request could not be completed." }, 500);
  }
});
