# 08 · Runner, scenario matrix and results (L-08)

## What it is, in plain words

`./mvnw -q compile exec:java` runs `runner.Main`. It builds one seeded `Workload` and runs it 12
times: ledger {LOCKING, CAS} × threads {PLATFORM, VIRTUAL} × faults {NONE, FLAKY, OUTAGE}. Each cell
wires the full stack: ledger → engine → `OutboxPublisher` → `ResilientPublisher` → `FakeGeneralLedger`.
It prints the machine (CPU, cores, OS, JDK) and a table, and writes `results/results.json` and
`results/results.md`.

- **Workload:** transfer `i` comes from a random generator seeded by (seed, i) alone, so every cell
  gets exactly the same transfers and any thread can make any transfer without storing a million.
  `hotPercent` of transfers stay within the first `hotAccounts` accounts: the contention knob.
  `duplicatePercent` resend an earlier transfer unchanged, as a retried message would.
- **Closed-loop clients:** 64 virtual threads each submit, wait for the answer, record the latency,
  and repeat. Throughput = transfers ÷ time until the engine is drained. Latency = submit to answer.
  (An open loop that ignores answers would measure queue overflow instead of the engine.)
- **Percentiles:** nearest rank over every latency: p50 is the typical case, p99 the slow tail.
- **Checked, not trusted:** every cell ends with `InvariantChecker.checkRun`; a FAIL names its first
  counterexample, and `Main` exits with code 1.
- **Warm-up:** one unreported run per ledger first, so the JIT has compiled the hot paths.
- **The first run found two design bugs** (fixed in L-06's outbox, test-first): (1) new postings
  queued behind a non-empty outbox, so under FLAKY the single relay became the bottleneck and the GL
  never caught up (an invariant FAIL); (2) publishing on the worker thread let GL latency and
  back-off cut FLAKY throughput to about 730/s. Measuring is how you find what tests did not ask.

## Interview questions

1. **Why is the workload generated from (seed, index) instead of stored?**
   Same transfers in every cell (a fair comparison), repeatable across runs, constant memory, and
   any client thread can produce transfer `i` without coordination.
2. **Closed loop or open loop, and why does it matter?**
   Closed loop: a fixed number of clients, each waits for its answer. It measures the engine's
   capacity and latency at that concurrency. An open loop that fires regardless of answers measures
   how fast the queue overflows (`QUEUE_FULL`), a different question.
3. **Why report p99 and not only the average?**
   Averages hide the tail. A few slow transfers (lock convoys, CAS retry storms, retry back-off)
   barely move the mean but are what users feel. p99 shows them.
4. **Why warm up before measuring on the JVM?**
   The JIT compiles hot code only after it has run a while. Without a warm-up the first cell runs
   partly interpreted and looks slower for reasons that have nothing to do with its design.
5. **What did the first real run teach you?**
   Two bugs no unit test had asked about: a serial relay bottleneck and GL latency on worker threads.
   Both passed every test at small scale; only load showed them. Fixed test-first, then re-measured.
