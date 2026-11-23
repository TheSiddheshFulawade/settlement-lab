# fix-01 · A latency test that failed only on Windows

## What happened

`./mvnw.cmd verify` failed on a Windows laptop although CI (Ubuntu) had always been green:
`FakeGeneralLedgerTest.addsLatency` asked for a 20 ms latency and measured **18.7 ms**. The test
asserts a lower bound: "a call takes at least the configured latency". That is the fake general
ledger's promise, so the test was right and the code was wrong.

## Why

`FakeGeneralLedger.publish` slept once: `LockSupport.parkNanos(latency)`. That gives no minimum:

- **`parkNanos` may return early.** Its contract allows spurious wake-ups, returning "for no
  reason", and it returns at once if the thread is interrupted.
- **Windows timers are coarse.** The OS wakes sleeping threads on a timer tick (historically about
  15.6 ms, often 1 ms when some process raises the resolution). A wait that is not a whole number of
  ticks gets rounded, and the rounding can come out *short*. Linux uses high-resolution timers, so
  the bug never showed up there.
- `System.nanoTime()` measures accurately on both OSes, so the test saw the truth: 18.7 ms.

A new test, `latencyIsAMinimumOnEveryCall` (25 calls of 3 ms, each one checked), failed in 3 of 3
runs before the fix. The old test failed in 1 of 3.

## The fix

`waitAtLeast(latency)` computes a **deadline** on `System.nanoTime()`, then parks for whatever is
left, in a loop, until the deadline has passed. An early wake just means one more short park. The
invariant ("returns no sooner than `latency`") is written next to the loop. It uses real time, not
the injectable `nanoClock`: a test clock that stands still would make the loop wait forever.

The engine's other waits already worked this way. `Condition.awaitNanos` returns the time still
left, and `InFlight.awaitIdle`, `OutboxPublisher.drain` and `ArrayBlockingQueue.offer` loop on it.
The other timing tests check only generous upper bounds, or a result that holds however long the
wait lasts. CI now also runs on `windows-latest`, so a problem like this shows up before merge.

**Rule:** a sleep is a hint, not a promise. If the code promises a minimum, loop to a deadline.
If a test needs to wait for something, wait on a condition (Awaitility), never a sleep.

## Interview questions

1. **Why did the test fail on Windows but never on Linux?**
   `parkNanos` gives no minimum: it can wake spuriously, and on Windows the wake-up is rounded to
   the OS timer tick, so it can come early. Linux's high-resolution timers happened to wake on time
   or late. The test checked a lower bound, so only the early wake broke it.
2. **Why fix the code and not loosen the test to, say, ≥ 15 ms?**
   The fake GL promises a minimum latency, and the runner's results rely on that. Loosening the
   test would hide a broken promise. The tolerance would also depend on the OS tick, which is a
   flaky test waiting to happen.
3. **Why is the deadline taken from `System.nanoTime()` and not `System.currentTimeMillis()`?**
   `nanoTime` is monotonic: it never jumps when the wall clock changes (NTP, a manual change, a
   leap second), and it has fine resolution. `currentTimeMillis` is wall time, which can go
   backwards and is only meant for timestamps. Elapsed time is always `nanoTime` minus `nanoTime`.
