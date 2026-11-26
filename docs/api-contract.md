# Runs API contract (v2.1)

For the portfolio `/lab` page. Source of truth for behaviour: SPEC.md §18 (REQ-RUN-001 to 012).
**Status: approved 2026-11-23, amended 2026-11-24 (twice; the second only documents existing behaviour) and
2026-11-25 (one more 404 problem type, `not-found`).** Base URL in production: `https://api.siddheshfulawade.com`.

All bodies are JSON (`application/json`), money is in plain **minor units** (`long`), times are
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

All fields are required; unknown fields are rejected. A request body may be at most 16 KB.
A `runId` matches `[A-Za-z0-9-]{1,64}`. Transfers are submitted at a steady 2,000 per second.
`OUTAGE` refuses general-ledger calls from 1 s to 4 s after the start.

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

Events have no `id:` and there is no `Last-Event-ID` replay: a reconnecting client gets the current
metrics, or the `report` if the run is done.

`HEAD` on this URL answers 405 with `Allow: GET` (for an unknown run too) and takes no subscriber place.

### `metrics` (every 250 ms while the run is active, drain included)

```
event: metrics
data: {"elapsedMs":3002,"submitted":6005,"posted":5907,"rejected":6,"duplicates":92,
data:  "throughputPerSecond":1978,"p99Micros":50,"queueDepth":0,"queueCapacity":1024,
data:  "glRetries":752,"breakerState":"CLOSED","outboxSize":1567}
```

(Shown wrapped; the server sends one `data:` line.)

| Field | Meaning |
| ----- | ------- |
| `elapsedMs` | time since the run started |
| `submitted`, `posted`, `rejected`, `duplicates` | running counts (`rejected` = funds + invalid + queue full) |
| `throughputPerSecond` | postings per second over the last 1 s; the paced rate, **not** engine capacity (benchmarks are in the README) |
| `p99Micros` | p99 submit-to-outcome latency so far |
| `queueDepth` / `queueCapacity` | intake queue fill |
| `glRetries` | general-ledger retries so far |
| `breakerState` | `CLOSED`, `OPEN`, `HALF_OPEN` |
| `outboxSize` | postings waiting for the general ledger (waiting for a sender, being sent, or in the outbox) |

### `report` (once, then the server closes the stream)

```json
{
  "runId": "r-7f3c2a9e",
  "status": "DONE",
  "stoppedEarly": false,
  "settings": { "ledger": "LOCKING", "threads": "VIRTUAL", "faults": "FLAKY", "transfers": 20000, "duplicatePercent": 2 },
  "durationMs": 13704,
  "transfersCompleted": 20000,
  "openingTotal": 10000000,
  "closingTotal": 10000000,
  "duplicatesSent": 395,
  "duplicatesApplied": 0,
  "throughputPerSecond": 1425,
  "p50Micros": 23,
  "p99Micros": 50,
  "glRetries": 3443,
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

Both examples are one measured run on a GitHub Actions Linux runner (4 CPUs, JDK 25, 2026-11-24); real
one-CPU Cloud Run speed is to verify at V2-06. 1,000 accounts × 10,000 = 10,000,000 in total, and 20,000
transfers = 19,534 postings + 71 rejected + 395 duplicates. `durationMs` runs from the 202 to the end of
the drain (drain included): submitting takes 10 s, then FLAKY needs about 4 s more to deliver what is
still waiting (`outboxSize` was 5,069 at 10 s). `throughputPerSecond` = postings ÷ `durationMs`:
19,534 ÷ 13.704 s = 1,425. It is a paced rate, at most 2,000, not engine capacity.
A `counterexample` is a string of at most 500 characters, or null.

`stoppedEarly: true` means the 20 s limit ended the run; the checks still cover every transfer
that was submitted. Connecting after the run finished sends the `report` at once. A run's report is
kept for 10 minutes; after that the run answers 404.

The report arrives within 60 s of the 202. A report that becomes ready at 60 s or later is dropped: the
stream closes without it and `GET` answers 404, the same as for an expired run. So if the stream closes
without a report, `GET` the run: 404 means it expired (the server lost its CPU or restarted).

`GET /api/v1/runs/{runId}` returns `{"runId", "status"}` while running (`RUNNING`), or the same
body as the `report` event once `DONE`. There are no other statuses.

## 3. Errors and limits

| Status | When | Headers | Problem `type` suffix |
| ------ | ---- | ------- | --------------------- |
| 400 | missing, unknown or out-of-range field, malformed JSON | | `invalid-run-request` |
| 404 | unknown or expired `runId` | | `run-not-found` |
| 404 | any path that is not part of this contract (public service) | | `not-found` |
| 413 | request body over 16 KB | | `body-too-large` |
| 429 | this IP started 5 runs in the last hour, or a 3rd events subscriber on one run | `Retry-After` (s) | `rate-limited` |
| 503 | 2 runs already active, or 60 runs this hour across all visitors | `Retry-After` (s) | `busy` |

Every 429 and 503 body carries `retryAfterSeconds`, equal to `Retry-After`. Example:

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
configuration; expected `https://develop.portfolio-7qt.pages.dev`, which answered 404 on 2026-11-23).
This covers the runs endpoints, the event stream, `/actuator/health` and every error body. Methods `GET`, `POST`; request header `Content-Type`; exposed headers `Location`,
`Retry-After`. No credentials.

## 5. Health

`GET /actuator/health` → `{"status":"UP"}`, no details, independent of any database. The page calls it on load and shows "starting the
engine" until it answers (cold start, ADR-0002).
