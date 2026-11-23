# ADR-0002: PostgreSQL ledger, and free hosting for the API and database

- Status: accepted
- Date: 2026-11-21

## Context

The engine is in memory, in one process. The obvious interview challenge is "real banks do this in a
database, across many servers". The next step adds a database-backed ledger that passes the same
invariant tests. The live demo (v2) also needs the API and the database hosted publicly, and the
running cost must stay at zero: the domain is the only paid item.

## Decision

### Database: PostgreSQL

- A `PostgresLedger` next to `LockingLedger` and `CasLedger`, behind the same `Ledger` port:
  pessimistic mode (`SELECT … FOR UPDATE` on both account rows, in ascending id order) and optimistic
  mode (a `version` column, retried on conflict).
- Idempotency that survives restarts: a unique constraint on the transfer id.
- Transactional outbox: the posting and its outbox row are written in the same transaction; a relay
  publishes outbox rows to the general ledger.
- Plain JDBC (no JPA) so every SQL statement and lock is visible; schema migrations with Flyway.
- Tests: Testcontainers runs the same jqwik and stress suites against a real PostgreSQL.

**Not Oracle**, although Oracle is common in banks: the user's employer's Oracle database and tools
must never be used for personal work. Oracle's free options are heavier to run in tests and have no
comparable free hosting. The SQL patterns used (row locks in id order, unique constraints, version
columns, `FOR UPDATE SKIP LOCKED` for the relay) work the same way on Oracle, which is the interview
answer.

### Hosting (decided now, deployed in v2 only with the user's explicit yes)

| Part                        | Where                                                                                                        | Cost               |
| --------------------------- | ------------------------------------------------------------------------------------------------------------ | ------------------ |
| Website and `/lab` page     | Cloudflare Pages (portfolio repo)                                                                            | free               |
| API (Spring Boot container) | Google Cloud Run, `asia-southeast1` (Singapore), request-based billing, min instances 0, **max instances 1** | free tier          |
| Database                    | Neon PostgreSQL free plan, AWS `ap-southeast-1` (Singapore)                                                  | free               |
| DNS, TLS, rate-limit rule   | Cloudflare (`api.siddheshfulawade.com`)                                                                      | free               |
| Images                      | Artifact Registry, keep the last 2 images                                                                    | free tier (0.5 GB) |

API and database sit in the same city, so each SQL round trip is a few milliseconds; visitors in
India reach Singapore in about 60 ms.

## Alternatives considered

- **Oracle Cloud Always Free VM** (app and PostgreSQL on one server): free and always on, but a VM
  must be patched, secured and monitored by hand, and free-tier capacity and idle rules are outside
  our control. Not chosen.
- **Render free**: sleeps after 15 minutes, about a minute to wake, small memory for Spring Boot, and
  its free database expires. Rejected.
- **Small paid VPS** (for example Hetzner, about €4.5 to €5.5 per month): always on and simple, but
  costs about ₹5,500 to ₹6,500 per year and must be maintained. Kept as the paid fallback.
- **AWS or Azure**: free credits or 12 months, then paid. Rejected.

## Consequences

- Cold start: with zero minimum instances, the first request after idle starts the container (a few
  seconds) and Neon wakes the database (under a second). The Lab page pings `/health` on load and shows
  "starting the engine" until it answers.
- Cost guard: Cloud Run needs a billing account. Max instances 1, the API's own run limits, a
  Cloudflare rate-limit rule and a budget alert at ₹100 keep any spend near zero. Worst case, if the
  limits failed and one instance ran flat out all month, the overage would be a few thousand rupees,
  and the budget alert would fire long before that.
- Testcontainers needs Docker: on the laptop (Docker Desktop) and on Ubuntu CI runners. GitHub's
  Windows runners cannot run Linux containers, so database tests run on Ubuntu only; engine tests stay
  on both.
- New dependencies (PostgreSQL JDBC driver, Flyway, Testcontainers) get entries in ADR-0001 when added.
- Free plans change: re-check Cloud Run and Neon limits right before deploying.
- PostgreSQL version (2026-11-23): tests use PostgreSQL 18 (`postgres:18.7-alpine3.24`). At V2-06,
  the Neon project is created on the same major version; if Neon cannot offer it, the test image moves
  to Neon's major version, and the version used is recorded here.
