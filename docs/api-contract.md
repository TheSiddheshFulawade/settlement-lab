# Runs API contract (v2.1)

For the portfolio `/lab` page. Source of truth for behaviour: SPEC.md §18 (REQ-RUN-001 to 012).
**Status: approved 2026-11-23.** Base URL in production: `https://api.siddheshfulawade.com`.

All bodies are JSON (`application/json`), money is in **minor units** (`long`), times are
milliseconds or microseconds as the field name says. Errors are RFC 9457 problem details
(`application/problem+json`).

## 1. Start a run

`POST /api/v1/runs`

```json
{
  "ledger": "LOCKING",
  "threads": "VIRTUAL",
  "faults": "FLAKY",
  "transfers": 20000,
  "duplicatePercent": 2
}
```

| Field | Type | Allowed |
| ----- | ---- | ------- |
| `ledger` | string | `LOCKING`, `CAS` |
| `threads` | string | `PLATFORM`, `VIRTUAL` |
| `faults` | string | `NONE`, `FLAKY`, `OUTAGE` |
| `transfers` | integer | 1 to 20,000 |
| `duplicatePercent` | integer | 0 to 10 |

All fields are required; unknown fields are rejected.

**202 Accepted**, header `Location: /api/v1/runs/{runId}`:

```json
{
  "runId": "r-7f3c2a9e",
  "status": "RUNNING",
  "eventsUrl": "/api/v1/runs/r-7f3c2a9e/events",
  "limits": { "maxSeconds": 20, "runsPerHourPerIp": 5, "concurrentRuns": 2 }
}
```

## 2. Watch a run

`GET /api/v1/runs/{runId}/events` with `Accept: text/event-stream` (use `EventSource`).

### `metrics` (every 250 ms while the run is active)

```
event: metrics
id: 12
data: {"elapsedMs":3000,"submitted":5400,"posted":5120,"rejected":31,"duplicates":104,
data:  "throughputPerSecond":225608,"p99Micros":735,"queueDepth":12,"queueCapacity":1024,
data:  "glRetries":17,"breakerState":"CLOSED","outboxSize":0}
```

(Shown wrapped; the server sends one `data:` line.)

| Field | Meaning |
| ----- | ------- |
| `elapsedMs` | time since the run started |
| `submitted`, `posted`, `rejected`, `duplicates` | running counts (`rejected` = funds + invalid + queue full) |
| `throughputPerSecond` | postings per second over the last 1 s |
| `p99Micros` | p99 submit-to-outcome latency so far |
| `queueDepth` / `queueCapacity` | intake queue fill |
| `glRetries` | general-ledger retries so far |
| `breakerState` | `CLOSED`, `OPEN`, `HALF_OPEN` |
| `outboxSize` | postings waiting for the general ledger |

### `report` (once, then the server closes the stream)

```json
{
  "runId": "r-7f3c2a9e",
  "status": "DONE",
  "stoppedEarly": false,
  "settings": { "ledger": "LOCKING", "threads": "VIRTUAL", "faults": "FLAKY", "transfers": 20000, "duplicatePercent": 2 },
  "durationMs": 4120,
  "transfersCompleted": 20000,
  "openingTotal": 1000000000,
  "closingTotal": 1000000000,
  "duplicatesSent": 400,
  "duplicatesApplied": 0,
  "throughputPerSecond": 225608,
  "p50Micros": 210,
  "p99Micros": 735,
  "glRetries": 61,
  "breakerOpens": 0,
  "invariants": [
    { "name": "TOTAL_CONSERVED", "passed": true, "counterexample": null },
    { "name": "NO_NEGATIVE_BALANCE", "passed": true, "counterexample": null },
    { "name": "POSTED_AT_MOST_ONCE", "passed": true, "counterexample": null },
    { "name": "POSTINGS_EXPLAIN_BALANCES", "passed": true, "counterexample": null },
    { "name": "POSTINGS_MATCH_ACCEPTED", "passed": true, "counterexample": null },
    { "name": "GL_MATCHES_POSTINGS", "passed": true, "counterexample": null }
  ],
  "allPassed": true
}
```

`stoppedEarly: true` means the 20 s limit ended the run; the checks still cover every transfer
that was submitted. Connecting after the run finished sends the `report` at once. A run's report is
kept for 10 minutes; after that the events URL answers 404.

`GET /api/v1/runs/{runId}` returns `{"runId", "status"}` while running (`RUNNING`), or the same
body as the `report` event once `DONE`.

## 3. Errors and limits

| Status | When | Headers | Problem `type` suffix |
| ------ | ---- | ------- | --------------------- |
| 400 | missing, unknown or out-of-range field, malformed JSON | | `invalid-run-request` |
| 404 | unknown or expired `runId` | | `run-not-found` |
| 429 | this IP started 5 runs in the last hour, or a 3rd events subscriber on one run | `Retry-After` (s) | `rate-limited` |
| 503 | 2 runs already active, or 60 runs this hour across all visitors | `Retry-After` (s) | `busy` |

Example:

```json
{
  "type": "https://api.siddheshfulawade.com/problems/rate-limited",
  "title": "Too many runs",
  "status": 429,
  "detail": "5 runs per hour per visitor. Try again in 1,260 s.",
  "retryAfterSeconds": 1260
}
```

## 4. CORS

Allowed origins: `https://siddheshfulawade.com` and the Cloudflare Pages preview origin (set by
configuration). Methods `GET`, `POST`; request header `Content-Type`; exposed headers `Location`,
`Retry-After`. No credentials.

## 5. Health

`GET /actuator/health` → `{"status":"UP"}`. The page calls it on load and shows "starting the
engine" until it answers (cold start, ADR-0002).
