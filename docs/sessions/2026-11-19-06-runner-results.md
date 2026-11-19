# 2026-11-19 · Session 6 · Runner, results, README (L-08, L-09)

- **Done:** PR #3 (L-07) self-reviewed and merged on green CI. L-08 runner (seeded workload, closed-loop clients, warm-up, 12-cell matrix, console/Markdown/JSON) and L-09 README plus a committed run on `feature/day-4`. All of SPEC.md section 7 is now ticked.
- **Found by measuring, fixed test-first (L-06):** queueing behind a non-empty outbox made the single relay a bottleneck (FLAKY never caught up: invariant FAIL); publishing on worker threads cut FLAKY to about 730/s. Now direct publish first, on sender threads.
- **Results (JDK 21, 4-core cloud container, one run):** every cell PASS; locking 2.8× to 4.6× CAS; no clear platform/virtual throughput winner, virtual lower p99 in all pairs; OUTAGE up to about 30 % slower.
- **TODO-ME (yours):** `PercentilesTest.smallSamples`; JDK 25 run on your laptop (docs/learn/09-results.md).
- **Next:** v2 (Spring Boot API) per SPEC.md; repeat the run on JDK 25 and more cores.
- **Open questions:** (1) Silence SLF4J's "no provider" notice with `slf4j-nop`? (2) Raise the enforcer minimum to JDK 25? (3) Keep JUnit 5.14 until jqwik supports Platform 6?
- **Quiz:**
  1. Why is transfer `i` generated from (seed, i) and not stored?
  2. Closed loop vs open loop: what does each measure?
  3. What two bugs did the first runner run expose, and why had no unit test caught them?
  4. Why did CAS lose to ordered locking here, and when might it win?
  5. Why report p99, and why warm up the JVM first?
