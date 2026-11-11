import { createClient } from "npm:@supabase/supabase-js@2";
import { importX509, jwtVerify, decodeProtectedHeader } from "npm:jose@5.10.0";

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export type VerifiedUser = {
  id: string;
  token: string;
};

let firebaseCertificates: Record<string, string> | null = null;
let firebaseCertificatesExpireAt = 0;

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export async function verifiedUser(request: Request): Promise<VerifiedUser | null> {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer\s+([^\s]+)$/i.exec(authorization);
  if (!match) return null;

  const projectId = Deno.env.get("FIREBASE_PROJECT_ID");
  if (!projectId || !/^[a-z0-9][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) {
    throw new Error("Firebase project configuration is invalid.");
  }

  try {
    const token = match[1];
    const header = decodeProtectedHeader(token);
    if (header.alg !== "RS256" || typeof header.kid !== "string") return null;
    const certificates = await loadFirebaseCertificates();
    const certificate = certificates[header.kid];
    if (!certificate) return null;

    const { payload } = await jwtVerify(token, await importX509(certificate, "RS256"), {
      algorithms: ["RS256"],
      issuer: `https://securetoken.google.com/${projectId}`,
      audience: projectId,
      requiredClaims: ["sub", "iat", "exp", "auth_time"],
    });
    if (typeof payload.sub !== "string" || payload.sub.length < 1 || payload.sub.length > 128) {
      return null;
    }
    return { id: payload.sub, token };
  } catch {
    return null;
  }
}

async function loadFirebaseCertificates(): Promise<Record<string, string>> {
  if (firebaseCertificates && Date.now() < firebaseCertificatesExpireAt) {
    return firebaseCertificates;
  }

  const response = await fetch(
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com",
  );
  if (!response.ok) throw new Error("Firebase signing certificates are unavailable.");

  const certificates: unknown = await response.json();
  if (typeof certificates !== "object" || certificates === null || Array.isArray(certificates)) {
    throw new Error("Firebase signing certificates are invalid.");
  }
  const validCertificates = Object.fromEntries(
    Object.entries(certificates).filter((entry): entry is [string, string] =>
      typeof entry[1] === "string" && entry[1].includes("BEGIN CERTIFICATE")
    ),
  );
  if (Object.keys(validCertificates).length === 0) {
    throw new Error("Firebase signing certificates are empty.");
  }

  const maxAge = /max-age=(\d+)/i.exec(response.headers.get("cache-control") ?? "");
  const cacheMilliseconds = Math.max(60, Number(maxAge?.[1] ?? 300)) * 1000;
  firebaseCertificates = validCertificates;
  firebaseCertificatesExpireAt = Date.now() + cacheMilliseconds;
  return validCertificates;
}

export function memoryDatabaseClient(user: VerifiedUser) {
  const url = Deno.env.get("SUPABASE_URL");
  const publishableKey = Deno.env.get("SUPABASE_ANON_KEY") ??
    Deno.env.get("SUPABASE_PUBLISHABLE_KEY");
  if (!url || !publishableKey) {
    throw new Error("Supabase memory database configuration is incomplete.");
  }
  return createClient(url, publishableKey, {
    global: { headers: { Authorization: `Bearer ${user.token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export async function readJsonObject(
  request: Request,
  maximumCharacters = 500_000,
): Promise<Record<string, unknown> | null> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) return null;
  const raw = await request.text();
  if (raw.length > maximumCharacters) return null;
  try {
    const body: unknown = JSON.parse(raw);
    if (typeof body !== "object" || body === null || Array.isArray(body)) return null;
    return body as Record<string, unknown>;
  } catch {
    return null;
  }
}
