import { test } from "node:test";
import assert from "node:assert/strict";
import worker, {
  validateReport,
  REASONS,
  MAX_BODY_BYTES,
  MAX_CONTENT_CHARS,
  MAX_NOTE_CHARS,
} from "./worker.js";

const valid = () => ({
  schema: 1,
  reason: "harmful_or_offensive",
  note: "rude",
  assistantContent: "the response",
  provider: "GEMINI",
  model: "gemini-2.5-flash",
  appVersion: "1.2.0 (1)",
  distribution: "play",
  locale: "zh-TW",
  androidSdk: 35,
  createdAt: 1700000000000,
});

class FakeKv {
  constructor() {
    this.items = [];
  }
  async put(key, value, options) {
    this.items.push({ key, value, options });
  }
}

const post = (body, headers = { "content-type": "application/json" }) =>
  new Request("https://reports.example.com/report", {
    method: "POST",
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

test("a valid report is accepted and only known fields are kept", () => {
  const result = validateReport({ ...valid(), ip: "1.2.3.4", extra: { a: 1 } });
  assert.equal(result.ok, true);
  assert.deepEqual(Object.keys(result.report).sort(), Object.keys(valid()).sort());
});

test("the user's message is optional but kept when present", () => {
  assert.equal("userContent" in validateReport(valid()).report, false);
  assert.equal(validateReport({ ...valid(), userContent: "question" }).report.userContent, "question");
});

test("every reason the app can send is accepted", () => {
  for (const reason of REASONS) {
    assert.equal(validateReport({ ...valid(), reason }).ok, true, reason);
  }
});

test("bad input is rejected", () => {
  const cases = [
    null,
    [],
    "text",
    { ...valid(), schema: 2 },
    { ...valid(), reason: "spam" },
    { ...valid(), assistantContent: "" },
    { ...valid(), assistantContent: "x".repeat(MAX_CONTENT_CHARS + 1) },
    { ...valid(), note: "n".repeat(MAX_NOTE_CHARS + 1) },
    { ...valid(), note: 5 },
    { ...valid(), userContent: 7 },
    { ...valid(), provider: "" },
    { ...valid(), androidSdk: "35" },
    { ...valid(), createdAt: -1 },
  ];
  for (const input of cases) {
    assert.equal(validateReport(input).ok, false, JSON.stringify(input)?.slice(0, 60));
  }
});

test("POST stores the report with an expiry and answers 204", async () => {
  const kv = new FakeKv();
  const response = await worker.fetch(post(valid()), { REPORTS: kv });

  assert.equal(response.status, 204);
  assert.equal(kv.items.length, 1);
  assert.match(kv.items[0].key, /^report:\d{15}:[0-9a-f-]{36}$/);
  assert.equal(kv.items[0].options.expirationTtl, 90 * 24 * 60 * 60);
  const stored = JSON.parse(kv.items[0].value);
  assert.equal(stored.assistantContent, "the response");
  assert.equal(typeof stored.receivedAt, "number");
});

test("the retention period is configurable", async () => {
  const kv = new FakeKv();
  await worker.fetch(post(valid()), { REPORTS: kv, RETENTION_DAYS: "30" });
  assert.equal(kv.items[0].options.expirationTtl, 30 * 24 * 60 * 60);
});

test("nothing identifying is stored", async () => {
  const kv = new FakeKv();
  const request = post(valid(), {
    "content-type": "application/json",
    "cf-connecting-ip": "203.0.113.9",
    "user-agent": "GlassesAiCompanion/1.2.0",
  });
  await worker.fetch(request, { REPORTS: kv });
  assert.equal(kv.items[0].value.includes("203.0.113.9"), false);
});

test("other methods and content types are refused", async () => {
  const kv = new FakeKv();
  const get = await worker.fetch(new Request("https://reports.example.com/report"), { REPORTS: kv });
  assert.equal(get.status, 405);

  const text = await worker.fetch(post("hello", { "content-type": "text/plain" }), { REPORTS: kv });
  assert.equal(text.status, 415);
  assert.equal(kv.items.length, 0);
});

test("malformed or oversized bodies are refused", async () => {
  const kv = new FakeKv();
  assert.equal((await worker.fetch(post("{not json"), { REPORTS: kv })).status, 400);
  const big = JSON.stringify({ ...valid(), note: "x".repeat(MAX_BODY_BYTES) });
  assert.equal((await worker.fetch(post(big), { REPORTS: kv })).status, 413);
  assert.equal(kv.items.length, 0);
});
