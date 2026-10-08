/**
 * Reference receiver for the in-app "report this AI response" feature.
 *
 * A tiny Cloudflare Worker: it accepts one JSON document per request, validates it strictly,
 * and stores it in Workers KV with an automatic expiry. Nothing else is collected: the body of a
 * report is never written to logs, and the client IP address is not stored.
 *
 * The app posts the JSON produced by `AiContentReport.toJson()` (schema 1).
 * Any other receiver that implements the same contract works just as well.
 */

export const SCHEMA_VERSION = 1;
export const MAX_BODY_BYTES = 32 * 1024;
export const MAX_CONTENT_CHARS = 8000;
export const MAX_NOTE_CHARS = 1000;
export const DEFAULT_RETENTION_DAYS = 90;

export const REASONS = new Set([
  "harmful_or_offensive",
  "sexual_content",
  "inaccurate_or_misleading",
  "privacy_or_personal_data",
  "other",
]);

const REQUIRED_STRINGS = ["provider", "model", "appVersion", "distribution", "locale"];
const SHORT_STRING_MAX = 200;

/**
 * Validate a decoded report. Returns `{ ok: true, report }` with only the known fields
 * (anything unexpected is dropped), or `{ ok: false, error }`.
 */
export function validateReport(input) {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    return { ok: false, error: "body must be a JSON object" };
  }
  if (input.schema !== SCHEMA_VERSION) {
    return { ok: false, error: "unsupported schema" };
  }
  if (!REASONS.has(input.reason)) {
    return { ok: false, error: "unknown reason" };
  }
  if (typeof input.assistantContent !== "string" || input.assistantContent.length === 0) {
    return { ok: false, error: "assistantContent is required" };
  }
  if (input.assistantContent.length > MAX_CONTENT_CHARS) {
    return { ok: false, error: "assistantContent too long" };
  }
  if (typeof input.note !== "string" || input.note.length > MAX_NOTE_CHARS) {
    return { ok: false, error: "note must be a string of at most " + MAX_NOTE_CHARS + " characters" };
  }
  if (input.userContent !== undefined) {
    if (typeof input.userContent !== "string" || input.userContent.length > MAX_CONTENT_CHARS) {
      return { ok: false, error: "userContent invalid" };
    }
  }
  for (const key of REQUIRED_STRINGS) {
    if (typeof input[key] !== "string" || input[key].length === 0 || input[key].length > SHORT_STRING_MAX) {
      return { ok: false, error: key + " invalid" };
    }
  }
  if (!Number.isInteger(input.androidSdk) || input.androidSdk < 1 || input.androidSdk > 1000) {
    return { ok: false, error: "androidSdk invalid" };
  }
  if (!Number.isInteger(input.createdAt) || input.createdAt < 0) {
    return { ok: false, error: "createdAt invalid" };
  }

  const report = {
    schema: input.schema,
    reason: input.reason,
    note: input.note,
    assistantContent: input.assistantContent,
    provider: input.provider,
    model: input.model,
    appVersion: input.appVersion,
    distribution: input.distribution,
    locale: input.locale,
    androidSdk: input.androidSdk,
    createdAt: input.createdAt,
  };
  if (typeof input.userContent === "string") {
    report.userContent = input.userContent;
  }
  return { ok: true, report };
}

function json(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function retentionSeconds(env) {
  const days = Number.parseInt(env.RETENTION_DAYS ?? "", 10);
  return (Number.isFinite(days) && days > 0 ? days : DEFAULT_RETENTION_DAYS) * 24 * 60 * 60;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json(405, { error: "method not allowed" });
    }
    const contentType = request.headers.get("content-type") ?? "";
    if (!contentType.toLowerCase().startsWith("application/json")) {
      return json(415, { error: "content-type must be application/json" });
    }

    const declared = Number.parseInt(request.headers.get("content-length") ?? "", 10);
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
      return json(413, { error: "payload too large" });
    }
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BODY_BYTES) {
      return json(413, { error: "payload too large" });
    }

    let decoded;
    try {
      decoded = JSON.parse(text);
    } catch {
      return json(400, { error: "invalid json" });
    }

    const result = validateReport(decoded);
    if (!result.ok) {
      return json(400, { error: result.error });
    }

    const receivedAt = Date.now();
    const id = crypto.randomUUID();
    await env.REPORTS.put(
      "report:" + String(receivedAt).padStart(15, "0") + ":" + id,
      JSON.stringify({ ...result.report, receivedAt }),
      { expirationTtl: retentionSeconds(env) },
    );

    return new Response(null, { status: 204 });
  },
};
