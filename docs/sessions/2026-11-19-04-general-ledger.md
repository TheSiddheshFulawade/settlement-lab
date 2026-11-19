# 2026-11-19 · Session 4 · General ledger, faults, outbox

- **Done (your choice: ledger returns the posting):** `Ledger.applyWithResult` returns `ApplyResult` (own commit). L-06: `PostingPublisher` port in domain; `gl` package with `GeneralLedgerPort`, `FakeGeneralLedger` (keyed by transfer id, duplicate publishes counted), `FaultProfile` (`NONE`, `FLAKY`, `DUPLICATE_ACKS`, `OUTAGE`), `OutboxPublisher` with an in-order relay; `GL_MATCHES_POSTINGS` invariant and `checkRun`. 128 tests green on JDK 21.
- **Checks:** dropping failed postings instead of keeping them failed 4 tests; 8 repeat runs of the GL and engine tests were clean. Fixed a race where a new posting could overtake the one the relay held.
- **TODO-ME (yours):** `FakeGeneralLedgerTest.refusesConflictingRepublish`.
- **Next:** L-07 Resilience4j retry and circuit breaker around the direct GL call (new dependency, ADR entry).
- **Open questions:** (1) `DUPLICATE_ACKS` is modelled as lost acks (stored, then TIMEOUT), so the sender re-publishes. OK? (2) The outbox is in memory only in v1. OK? (3) Earlier questions on PR #2 still open.
- **Quiz:**
  1. Why is exactly-once delivered as at-least-once plus an idempotent receiver?
  2. What is the difference between `ERROR` and `TIMEOUT` in the fake GL, and why does it matter for retries?
  3. Why does the caller's answer not wait for the general ledger?
  4. Why is `PostingPublisher` in `domain`, and what would break if `pipeline` imported `gl`?
  5. What is lost if the process crashes with entries in the outbox, and how would a database fix it?
