# 05 · Intake, worker pool and SettlementEngine (L-05)

## What it is, in plain words

`SettlementEngine.submit(transfer)` returns a `CompletionStage<Outcome>` and never blocks for long:

1. **Claim the id** in the `IdempotencyStore`. A duplicate gets the original's future (REQ-LAB-008).
2. **Validate** (`TransferValidator`). Invalid means answered `REJECTED_INVALID` at once, never queued.
   `engine.validate(t)` gives the reason (REQ-LAB-004).
3. **Offer** to an `ArrayBlockingQueue` for at most `offerTimeout`. If it is still full, answer
   `QUEUE_FULL` and **release** the id so a retry can work (REQ-LAB-009). This is back-pressure: when
   workers fall behind, callers are told quickly instead of memory growing or threads piling up.
4. A **worker** takes the job, calls `ledger.apply`, completes the future, then calls the listener.

**Worker pool, two modes, same cap N (REQ-LAB-010):**
- `PLATFORM`: N platform threads, each looping "poll the queue, run the job".
- `VIRTUAL`: one dispatcher takes a `Semaphore(N)` permit, then a job, and starts a virtual thread for
  it; the job returns the permit when done. Equal N makes the later benchmark compare threading
  models, not different amounts of parallelism.

**Shutdown:** `InFlight` counts accepted-but-unfinished jobs (a `ReentrantLock` + `Condition`, not
`synchronized`, because a virtual thread blocked in `synchronized` pins its carrier on JDK 21).
`drain(timeout)` waits for zero. `close()` refuses new work, drains, stops the loops, answers anything
still queued with `QUEUE_FULL` so no caller waits forever, then waits for running jobs to finish.

**A race found in self-review:** `submit` checked `closed`, then offered. A submit that passed the
check just before `close` could offer *after* close had emptied the queue and stopped the workers,
leaving its caller waiting forever. Fix: submitters hold the shared side of a
`ReentrantReadWriteLock` from the check until the offer is done; `close` takes the exclusive side to
flip `closed`. A test with 4 submitters racing `close` (20 rounds, both modes) failed before the fix.

**Failure handling:** if the ledger throws, the future completes exceptionally, `failed` is counted and
the id is released (nothing was applied). A throwing listener or publisher is counted (`listenerErrors`,
`publishErrors`), never allowed to kill a worker (before the fix, a throwing publisher killed the only
platform worker and every later transfer hung).

**Tests without sleeping:** `GatedLedger` holds every `apply` at a latch, so a test can fill the queue
or measure the concurrency cap exactly, then open the gate. Awaitility waits for conditions. The
stress test runs both modes × both ledgers with duplicates and ends with `InvariantChecker`. Removing
the semaphore cap made three engine tests fail.

## Interview questions

1. **Why a bounded queue and an offer timeout, instead of an unbounded queue or a blocking `put`?**
   An unbounded queue hides overload until memory runs out, and latency grows with no limit. A blocking
   `put` makes callers wait forever. A bounded queue plus a short timeout gives back-pressure: the
   caller learns "full" quickly and can back off or retry.
2. **Why is the concurrency cap the same N in both thread modes?**
   To compare like with like. Virtual threads are cheap to create, but the ledger work is CPU-bound and
   shares locks. Without the same cap, a difference in results could come from more parallelism rather
   than from the threading model.
3. **Why does `close()` complete leftovers with `QUEUE_FULL` instead of just stopping?**
   Every caller holds a future. A job left in the queue would never complete, so its caller would wait
   forever. `QUEUE_FULL` is honest: it was not processed, and the released id can be submitted again.
4. **Why `ReentrantLock` and `Condition` in `InFlight` rather than `synchronized` and `wait`?**
   Both are correct. On JDK 21, a virtual thread that blocks while inside `synchronized` pins its
   carrier platform thread, which can starve other virtual threads. `java.util.concurrent` locks do
   not pin. (JDK 24 removed most pinning, but the code must also run on 21.)
5. **Why is the in-flight count incremented before the offer, not after?**
   If it were incremented after, a worker could take and finish the job first and decrement to -1, or
   `drain` could see zero while a job is in the queue. Increment first, undo on a failed offer.
