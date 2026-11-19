# 02 · Domain types, LockingLedger and the invariant checker (L-02)

## What it is, in plain words

- **Value types** (`AccountId`, `TransferId`, `Money`, `Transfer`, `Posting`, `Outcome`) are records:
  immutable, with `equals` for free. `Money` is a `long` of minor units, so 0.1 + 0.2 problems cannot
  happen, and `plus`/`minus` use `Math.addExact` so overflow throws instead of wrapping to a negative.
- **`TransferValidator`** returns the first broken rule (`NON_POSITIVE_AMOUNT`, `SAME_ACCOUNT`,
  `UNKNOWN_SOURCE`, `UNKNOWN_TARGET`) as data, not an exception, so the engine can reply with a reason.
- **`LockingLedger`** keeps one `ReentrantLock` per account. `apply` validates, then locks the two
  accounts **lower id first**, checks funds, debits, credits and records a `Posting`, then unlocks in
  reverse. The check and both writes happen under both locks, so nobody sees a half-done transfer.
- **Why no deadlock:** a deadlock is a cycle "T1 holds X, wants Y; T2 holds Y, wants X". With one
  global order every thread only waits for a *higher* lock than any it holds, so a cycle would have
  to climb forever. We proved the test can see it: switching to "source first" made all four stress
  tests hang and fail on their timeout.
- **`snapshot()`** takes *all* locks in the same order, copies, and releases. Consistent, deadlock-free,
  but it pauses every transfer for one copy. Fine for checks, not for a hot path.
- **`InvariantChecker`** is pure functions over plain data: total conserved, no negative balance,
  each transfer posted at most once, replaying postings over opening balances gives the current
  balances, and postings equal the accepted set. Each failure names its first counterexample.
- **Tests:** unit tests per class; jqwik properties (any accounts, any transfers including invalid
  ones) including agreement with a 15-line sequential model; stress tests with 8 threads behind a
  `CountDownLatch`, daemon threads and a timeout. `LedgerKind` makes every suite run per ledger, so
  CasLedger (L-03) gets the same proofs for free.

## Interview questions

1. **Why does ordered locking prevent deadlock? Give the argument, not the slogan.**
   Deadlock needs a circular wait. If every thread acquires locks in one global order, a thread holding
   lock k only waits for locks above k. Following "waits for" edges therefore strictly increases the
   lock rank, so it can never return to its start: no cycle, so no deadlock.
2. **Why `ReentrantLock` per account instead of `synchronized` on the whole ledger?**
   One global lock is correct but serialises every transfer, even ones on unrelated accounts.
   Per-account locks let disjoint transfers run in parallel. `ReentrantLock` over `synchronized`
   also leaves room for `tryLock` with timeouts, and (on JDK 21) does not pin virtual threads.
3. **What would go wrong if the funds check happened before taking the locks?**
   Check-then-act race: two threads both see 100, both pass a 100 check, both debit, balance -100.
   The check must happen under the same lock as the write.
4. **How do you know the stress test would catch a deadlock rather than hang the build?**
   Workers are daemon threads, the test waits on a latch with a timeout and asserts it finished. We
   mutated the lock order and watched all four tests fail in 30 to 60 seconds with a clear message.
5. **Why compare against a sequential model in a property test?**
   It states "what correct means" in the simplest possible code. jqwik generates thousands of random
   cases, including invalid ones, and shrinks any disagreement to the smallest failing list of
   transfers, which is far stronger than a handful of hand-picked examples.
