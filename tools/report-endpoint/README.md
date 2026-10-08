# Report endpoint (reference implementation)

Google Play requires an app that generates content with AI to let users **report** that content
from inside the app. The app posts reports to an HTTPS endpoint of your choice. This folder is a
small, ready-to-deploy receiver for it: a Cloudflare Worker that validates each report and stores
it in Workers KV, where it **expires automatically** (90 days by default).

You are free to host the receiver elsewhere. The contract is only this:

- `POST` to an `https://` URL, `Content-Type: application/json`
- the body is the JSON described by `AiContentReport` (schema `1`, see
  `phone-app/src/main/java/io/github/zero2005x/glassesaicompanion/report/AiContentReport.kt`)
- any 2xx answer means "received"; a 4xx (except 429) means "refused, do not retry"

## What it stores, and what it does not

Stored per report: the reported AI response, the reason and note, the optional user message (only if
the user ticked the box), the AI provider and model name, app version, distribution (`play` or
`github`), locale, Android SDK level, the time the device created it and the time the server
received it.

Not stored: API keys, device identifiers, the client IP address, headers, or anything about other
conversations. The worker never logs report bodies.

## Deploy

```bash
npm install --global wrangler        # once
cd tools/report-endpoint
wrangler login
wrangler kv namespace create REPORTS # copy the printed id into wrangler.toml
wrangler deploy
```

`wrangler deploy` prints the URL, for example `https://glasses-ai-companion-reports.<you>.workers.dev`.
Put it into `local.properties` (not into git):

```properties
REPORT_ENDPOINT_URL=https://glasses-ai-companion-reports.<you>.workers.dev
```

A Play release build **fails** if `REPORT_ENDPOINT_URL` is not an `https://` URL.

### Protect it from abuse

The URL ships inside the app, so anyone can find it. The worker validates and size-limits every
request. Also add a **rate limiting rule** for the route in the Cloudflare dashboard (for example
10 requests per minute per IP) so one client cannot fill the namespace.

## Read, delete, retention

```bash
wrangler kv key list --binding REPORTS --prefix report:              # newest are last
wrangler kv key get  --binding REPORTS "report:<key>"                # read one report
wrangler kv key delete --binding REPORTS "report:<key>"              # delete one on request
```

Reports carry no identifier, so a deletion request has to quote the reported text; search for it
and delete the matching key. Keys expire on their own after `RETENTION_DAYS`.

Keep `RETENTION_DAYS` (here), `REPORT_RETENTION_DAYS` (in `ReportUi.kt`) and the privacy policy
(`privacy.md`) in agreement.

## Handle reports

Google asks developers to use reports to improve content filtering and moderation. Review new
reports regularly, group them by reason and provider, and record what you changed in response.

## Test

```bash
cd tools/report-endpoint
npm test
```