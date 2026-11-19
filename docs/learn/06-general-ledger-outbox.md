# 06 · Fake general ledger, faults and outbox (L-06)

## What it is, in plain words

After the ledger posts a transfer, the posting must reach the downstream **general ledger** (GL)
exactly once, even when the GL is slow, failing or down. The caller's answer does not wait for it:
the ledger posting is the truth; the GL is eventually consistent.

- **`Ledger.applyWithResult`** now returns `ApplyResult(outcome, posting)`, so the engine forwards the
  ledger's own posting (sequence included). `apply` stays as a default method.
- **`PostingPublisher`** is a port in `domain`. The engine (`pipeline`) only knows that interface;
  the GL adapter lives in `gl`. The package rules forbid `pipeline` to import `gl`, so the two meet
  only where the runner (or a test) wires them.
- **`FakeGeneralLedger`** stores entries in a map keyed by transfer id. A repeated publish returns
  `ALREADY_RECORDED` and is counted (`duplicatePublishes`): detected, never stored twice, never
  hidden. A different posting under the same id throws: that would be a real bug.
- **`FaultProfile`**: `NONE`; `FLAKY` (10 % `ERROR`: nothing stored; 5 % `TIMEOUT`: stored but the
  ack is lost; 1 ms latency); `DUPLICATE_ACKS` (30 % lost acks, so about a third are sent twice);
  `OUTAGE` (everything refused for a time window, with an injectable clock for tests). Draws come
  from one seeded generator, so a run is repeatable for the same order of calls.
- **`OutboxPublisher`**: try once directly; on failure (or if older entries are waiting) append to
  the **outbox**. One **relay** thread retries the head, in order, every `retryInterval`, putting it
  back at the front on failure. `drain` waits until nothing is pending; `outboxPeak` is reported.
- **Exactly once = at-least-once delivery + an idempotent receiver.** The relay may send a posting
  twice (a lost ack looks like a failure); the GL's key makes the second send harmless.
- **`InvariantChecker.checkRun`** adds `GL_MATCHES_POSTINGS`: after drain, the GL holds every ledger
  posting, unchanged, and nothing else.

Proven by an end-to-end test per profile (engine + fake GL + outbox, then `checkRun`), outbox order
tests with a switchable port, and seeded fault-rate tests. Mutation: dropping a failed posting
instead of keeping it failed 4 tests.

## Interview questions

1. **Why is "exactly once" achieved as at-least-once delivery plus an idempotent receiver?**
   A sender cannot tell "the GL never got it" from "the GL got it but the ack was lost". So it must
   resend, which risks duplicates. Keying the GL by transfer id makes a resend a no-op: the effect
   happens once even though delivery may happen twice.
2. **Why does the caller's future complete before the GL publish?**
   The ledger posting is the source of truth. Tying the answer to a slow or failing downstream would
   make the engine only as available as the GL. The outbox guarantees the GL catches up.
3. **Why do new postings queue behind the outbox instead of trying the GL directly?**
   It keeps the order the relay delivers in, and it stops every worker hammering a GL that is known
   to be failing. (L-07's circuit breaker formalises the second point.)
4. **Why is `PostingPublisher` in `domain`, not in `gl` or `pipeline`?**
   Dependency inversion: the engine depends on an abstraction it owns, the adapter depends on the
   domain, and `pipeline` never imports `gl`. ArchUnit enforces it.
5. **What is lost if the process crashes, and how would v2 fix it?**
   The outbox is in memory, so pending postings are lost on a crash. A real transactional outbox
   writes the posting and the outbox row in the same database transaction, and the relay reads from
   that table.
