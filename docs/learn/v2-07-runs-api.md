# v2-07 · The runs API (V2-07)

Source of truth: SPEC.md section 18 and `docs/api-contract.md`. All file names below are in
`api/src/main/java/com/siddheshfulawade/settlementlab/api/` unless stated.

## What it is, in plain words

A visitor on the portfolio page presses "run". The server builds a brand-new, in-memory settlement
engine, feeds it transfers at a steady 2,000 per second, streams live numbers while it works, and ends
with a `report` that says whether each of the six invariants held. Then it throws the engine away.

```bash
curl -X POST localhost:8080/api/v1/runs -H 'Content-Type: application/json' \
  -d '{"ledger":"LOCKING","threads":"VIRTUAL","faults":"FLAKY","transfers":20000,"duplicatePercent":2}'
# 202 {"runId":"r-7f3c2a9e","status":"RUNNING","eventsUrl":"/api/v1/runs/r-7f3c2a9e/events",...}
curl -N localhost:8080/api/v1/runs/r-7f3c2a9e/events
# event:metrics ... every 250 ms, then one event:report, then the stream closes
```

## The request's path through the code

```
 browser
   |  RunCorsFilter      (order +10)  adds CORS headers only for the two allowed origins
   |  ProdAllowListFilter(order +15)  prod only: anything but /api/v1/runs/** and /actuator/health -> 404
   |  BodyLimitFilter    (order +20)  any body-carrying method: more than 16 KB -> 413
   v
 RunController.start          parse JSON by hand (RunRequestParser) -> 400 if invalid
   |  ClientIp.resolve        who is the visitor?  (key kept in memory only)
   v
 RunService.start             expire old runs, then RunLimits.tryStart -> Admission
   |                          builds the engine, RunProgress, Run, RunDriver; starts a virtual thread
   v
 202 Accepted + Location + eventsUrl
 ...
 RunDriver (own thread)  submit at 2,000/s -> drain -> check invariants -> Run.finish(report)
 sampler (one thread)    every 250 ms -> Run.sample -> RunSubscriber buffers -> one thread per viewer
```

Step by step:

1. **Filters first.** The three filters run in a fixed order by `getOrder()`: CORS
   (`RunCorsFilter.java:46`), allow-list (`ProdAllowListFilter.java:26`), body cap
   (`BodyLimitFilter.java:34`). CORS is first so that even a 404 or 413 carries the CORS headers
   (the browser can then read the error).
2. **Validate before taking anything.** `RunController.start` (`RunController.java:52-54`) reads the
   already-capped body and parses it. A bad request throws 400 here, before any limit or slot is touched.
   The class comment (`:21-26`) gives the order: validation 400, per-visitor 429, active cap 503,
   global cap 503.
3. **Who is the visitor?** `ClientIp.resolve` (`ClientIp.java:65-82`) walks `X-Forwarded-For` from the
   right and returns the first address that is not one of our own trusted proxies. The left side is
   whatever the visitor typed, so it is never read. A garbage entry, or nothing left, means the
   connection's own address. IPv6 is reduced to its /64 (`:106`). No DNS lookup ever happens (`:16-19`).
4. **The limits.** `RunService.start` (`RunService.java:112-154`) first expires lost runs, then calls
   `RunLimits.tryStart` (`RunLimits.java:64-88`). One lock covers the whole check-and-record, so two
   racing requests cannot both take the last slot. The answer is an `Admission` (`Admission.java:14`).
5. **Build and go.** The service builds the engine, a `RunProgress`, a `Run` and a `RunDriver`, registers
   the run, schedules the sampler, then starts the driver on a virtual thread
   (`RunService.java:125-135`). The slot now belongs to the driver.
6. **Watching.** `RunController.events` (`RunController.java:87-115`) wraps an `SseEmitter` as a
   `RunSink` and calls `Run.subscribe`, which creates a `RunSubscriber` (`Run.java:155-177`).

## The core ideas

### 1. A slot is taken once and given back once (`Admission.java:10-12`, `:48-52`)

`release()` flips a flag with compareAndSet. A run can end three ways at the same moment (finish, expiry,
failure); only the first release counts, so the active count can neither leak nor go negative.
`RunService.start` also releases on its own failure path (`:151`), because then no driver exists to do it.

### 2. Check and record inside one lock (`RunLimits.java:16-18`)

"Is there room? Then take it" is two steps; done separately, two requests both see room. One lock around
both makes it one step. A refusal changes nothing (no window entry, no slot). Windows are sliding and
exactly one hour (`:120-132`); time comes from an injected `Clock`, so tests move it
(`RunLimits.java:20-21`).

### 3. Lifecycle: wall time, 60 s, RUNNING or DONE only (`Run.java:20-34`)

- **Wall time.** Deadlines read the injected `Clock` (`RunDriver.java:27-30`, `:135`, `:142`), because the
  promise is "20 s of real time", even if the instance lost its CPU for a while. Only the pacing uses
  `System.nanoTime()` (`:136`, `:145`): it decides when the next transfer is due, not how long the run may
  last.
- **20 s.** At the deadline the submit loop returns `true` (`:142-144`), the run still drains and checks,
  and the report says `stoppedEarly: true`.
- **60 s.** `Run.REPORT_LIMIT` (`Run.java:44`). The drain gives up at 55 s (`RunDriver.java:188`), and
  `finish` refuses a report that is 60 s or more old (`Run.java:217-220`). A run with no report after 60 s
  is expired by the next runs request (`RunService.java:178-192`): engine closed, streams closed, slot
  freed, and the id answers 404.
- **Only two statuses.** `Run.status()` is `RUNNING` until a report exists, then `DONE`
  (`Run.java:106-108`). Internally there is a third state, `ENDED` (expired or stopped, no report), but a
  client never sees it: the run is simply gone, 404. After a restart every old id is 404 too.
- **One-way machine under one lock.** `finish` and `end` both move only from RUNNING
  (`Run.java:223`, `:253`), so exactly one wins. A run is never both reported and expired.
  `subscribe` and `finish` use the same lock (`:158`, `:221`), so a viewer is either registered before the
  report (and handed it) or sees DONE (and is handed it at once).
- **Retention.** A DONE run is forgotten 10 minutes after it ended (`RunService.java:188`).

### 4. Pacing: a steady rate, never a busy spin (`RunDriver.java:124-164`)

Transfer `i` is due at `schedule + i * interval` (interval = 1 s / 2,000 = 500 us). Each turn submits
everything that is due (a wake-up can be a millisecond late, so a few at a time), then parks until the
next one (`:159`), never longer than 100 ms so a stop request is noticed. After a stall the driver
catches up by at most 100 transfers (`MAX_BURST`, `:56`, `:147-150`) and moves the schedule forward
instead, so the rate stays steady rather than bursting. `throughputPerSecond` in the report is
postings divided by `durationMs` (`:227`): the paced rate, not engine capacity. Also: 20,000 transfers
at 2,000/s take 10 s to submit, however fast the engine could go.

### 5. A viewer never slows the run (`RunSubscriber.java:9-23`)

The sampler and the driver only `offer` into a small bounded buffer under a short lock. A full buffer
drops the oldest event (`:65-67`). Because the report is offered once, last, after which no metrics are
offered, the report is always the newest entry and is never the one dropped. The slow socket write runs
on that viewer's own virtual thread (`:113`). Closing happens exactly once on a fresh virtual thread
(`:81-96`), the same idea as V2-04 (`v2-04-event-stream.md`). A failed write frees the viewer's place
(`:121-123`). At most 2 viewers per run (`Run.java:160`), the third gets 429.

### 6. Progress is lock-free statistics (`RunProgress.java:24-27`)

Latencies are stored as `nanos + 1` so that 0 means "claimed but not written yet", and a reader that
races a writer skips the slot instead of counting a fake zero (`:62-66`, `:88-92`). These are
statistics; the invariants are checked on the ledger itself (`RunDriver.java:206-211`). `outboxSize`
is `outbox().pendingCount()` (`RunProgress.java:120`): postings waiting for a sender, being sent, or in
the outbox, which is why it is not just the outbox table.

### 7. Cleanup on every path (`RunDriver.java:32-35`, `:102-113`)

In `finally`: close the engine, then release the slot, then forget the thread. Each step runs even if the
one before it throws. The driver is told to stop, not interrupted (`Run.java:243-244`), because an
interrupt could land inside the engine's own close.

## The edge filters

- **`BodyLimitFilter`** (16 KB): a declared `Content-Length` over the limit is refused without reading a
  byte; otherwise at most `MAX_BYTES + 1` bytes are read (`BodyLimitFilter.java:50-54`), so a chunked body
  that never ends cannot fill memory. It is chosen by method, not by path (`:39-45`), because
  `/runs;x=1` or `/%72uns` reach the same handler and a path list could be walked around.
- **`ProdAllowListFilter`**: a list of what is allowed, not of what is hidden (`:14-16`), so a controller
  added later is not public by accident. It compares the raw path (`:32`), so an odd spelling is refused.
- **`RunCorsFilter`**: an origin must match character for character (`:54`); no wildcard, no reflection,
  never credentials (`:18-22`). A foreign preflight gets 403 and no CORS header (`:72-76`). It is a servlet
  filter so that it also covers `/actuator/health` and every error body.

## What the audit found (and how it was fixed)

A security and quality review of this task found three real problems. Each now has a test.

1. **Finished runs kept their engine.** A DONE run stayed in the registry for 10 minutes, and the Run
   still pointed at `RunProgress::metrics`, which pointed at the whole `SettlementRuntime`: engine,
   postings, ledger, general ledger, outbox. The auditor measured about **7 MB per run, 419 MB at 60
   runs** on an instance sized 512 MiB. Fix: when a run leaves RUNNING it drops `metricsSource`
   (`Run.java:229`, `:259`) and the driver drops itself as its last step (`driverDone`, `:280-283`), so
   a kept run holds only its report. Test: `RunRegistryMemoryTest.java:66-96` holds only a `WeakReference` to
   the runtime and waits until the garbage collector has really freed it.
2. **The 16 KB cap could be walked around.** The cap covered only POST, but Spring's form-content filter
   reads a PUT, PATCH or DELETE form body fully into memory before any handler runs. Fix: the cap now
   covers every method except GET, HEAD and OPTIONS (`BodyLimitFilter.java:43-44`), and that filter is
   switched off, since nothing here takes forms (`application.yml:8-10`). Tests: `FormBodyLimitContract.java`
   sends declared, chunked, never-ending-chunked and 100 MB-declared bodies on raw sockets.
3. **HEAD took a viewer place.** Spring answers HEAD with the GET handler, so `HEAD .../events`
   subscribed and used one of the run's two places. Fix: an explicit HEAD handler that is a plain 405
   (`RunController.java:76-85`). Test: `RunEventsHeadHttpTest`.

## A test lesson: shared Spring context, shared money

Three test classes (`TransferControllerTest`, `TransferInputTest`, `DefaultProfileEndpointsTest`) use the
same `@SpringBootTest` settings, so Spring builds the context once and caches it. They therefore share
one engine, so one set of balances. All three moved money from account 1, and
`TransferControllerTest` expects `balance(1) == 900` (`TransferControllerTest.java:48`). Which class ran
first decided what it saw: 891 on Linux CI, 899 on Windows CI, 900 on a quiet laptop. The assertion was
right; the isolation was missing. The fix (commit `5c3c4d9`) gave each class its own accounts and changed
no assertion. Rule: tests that share a cached context must not share the data they assert on. A test
that passes on one operating system and fails on another is often an ordering problem, not a platform
problem.

## Results: how long does a 20,000-transfer FLAKY run take?

CI run 45344442008, 2026-11-24. Times in milliseconds. 20,000 transfers at 2,000/s, FLAKY and OUTAGE.
GitHub Actions runners with 4 CPUs. "One" means the JVM was limited to one processor
(`-XX:ActiveProcessorCount=1`); it is not pinned to one core. "x2" is the slower of two runs at once.
`park(1 ms)` is how long a 1 ms park really takes on that machine.

| Runner | park(1 ms) | CPUs | FLAKY x1 | FLAKY no latency | FLAKY x2 | OUTAGE x2 |
| ------ | ---------- | ---- | -------- | ---------------- | -------- | --------- |
| Linux JDK 21 | 1.10 | all | 13,809 | 12,295 | 13,763 | 9,999 |
| Linux JDK 21 | | one | 13,979 | 12,352 | 13,861 | 10,035 |
| Linux JDK 25 | 1.12 | all | 14,055 | 12,107 | 13,792 | 10,000 |
| Linux JDK 25 | | one | 13,765 | 12,299 | 13,702 | 10,054 |
| Windows JDK 21 | 14.91 | all | 34,844 | 13,816 | 34,542 | 10,004 |
| Windows JDK 21 | | one | 27,743 | 13,451 | 34,137 | 10,060 |
| Windows JDK 25 | 2.53 | all | 15,087 | 12,573 | 15,024 | 10,001 |
| Windows JDK 25 | | one | 15,307 | 12,403 | 15,450 | not recorded |

A repeat on run 45345508506 gave at most 13,949 (Linux 21), 13,927 (Linux 25), 34,860 (Windows 21) and
15,290 (Windows 25) for FLAKY. Every run passed all invariants, and none stopped early.

**Cause.** Submitting takes 10 s. The rest is delivery to the general ledger under FLAKY: each of 16
senders (`OutboxPublisher.java:51-59`, permit taken at `:74`, released at `:86`) holds its permit through
the 1 ms latency plus the retry back-off (50 ms, then 100 ms, jittered), so only so many postings can be
in flight at once. The calculated, not measured, ceiling was 13.4 s for 19,600 postings. Coarse Windows
timers explain the slow Windows rows: on JDK 21 a 1 ms park took about 15 ms (14.91 above), and removing
the latency brings Windows back to about 13.8 s. Local laptop runs (Windows 11, JDK 25) varied from 14.4 s
to 34.7 s and that variation is **not explained**; one local row was thrown away because the laptop slept
mid-run.

**Decision rule.** The slowest Linux time, 14.06 s, is at most 40 s, so 20,000 stays as the limit. The
real speed of one Cloud Run CPU is still to verify at V2-06.

**The sample run used for the contract examples** is CI run 45345508506, Linux JDK 25: 19,534 postings
+ 71 rejected + 395 duplicates = 20,000, in 13,704 ms.

## TODO-ME

`ClientIpTest.java:196-202` has `slashZeroTrustsEveryone`, marked `@Disabled("TODO-ME ...")`. Build a
`ClientIp` with `List.of("0.0.0.0/0")`, resolve `List.of("203.0.113.9")`, and assert the result is the
remote address. Run the class to check it. Then decide whether `0.0.0.0/0` should be refused at startup.
Not filled in here. (The other `TODO-ME` markers in `api/src/test` belong to V2-01 to V2-04.)

## Interview questions

1. **Why are the limits in memory, and why is that safe here?**
   The service runs as one instance (Cloud Run max instances 1, ADR-0002), so one in-memory map sees
   every request and the counts are exact. A database or Redis would cost a call per visitor and a new
   moving part. With several instances each would count separately, and the 5 per hour limit would
   become 5 per hour per instance; then the limiter would have to move out. The global cap of 60 per
   hour still bounds cost even if one IP key were forged. Restart forgets the windows, which is accepted.
2. **A request carries `X-Forwarded-For: 1.2.3.4, 9.9.9.9`. Which address is the visitor, and why not the
   first?**
   Walk from the right and take the first address outside our trusted proxies. With no trusted ranges
   configured that is the last entry, `9.9.9.9`, the one our own proxy appended. The first entry is typed
   by the client, so trusting it lets anyone pick a new identity for every request and dodge the 5 per hour
   limit. If an entry is not an IP, or none are left, fall back to the connection's address
   (`ClientIp.java:72-81`).
3. **How do you stop two requests both taking the last run slot?**
   Do the check and the record inside one lock (`RunLimits.tryStart`). A separate "if room, then take"
   is a race: both see room. The slot is then returned through an `Admission` whose release is a
   compareAndSet, so finish, expiry and failure can all try to release and exactly one succeeds.
4. **Why does a deadline use wall time but pacing use `nanoTime`?**
   The promise to the visitor is "20 s of real time, report within 60 s". If the process loses its CPU,
   a counter that only counts running time would not notice, so the deadline reads the wall clock
   (injected, so tests can move it). Pacing decides when the next transfer is due, so it needs a clock
   that only moves forward at real speed: `nanoTime`. A test clock that jumps would otherwise break the
   rate.
5. **A finished run held 7 MB, so 60 runs held 419 MB on a 512 MiB instance. How did you find and fix it?**
   An auditor measured it. The kept `Run` still referenced the metrics function, which referenced the
   whole engine stack. The fix is that a run drops its metrics source when it leaves RUNNING and the driver
   drops itself last, so a kept run holds only its report. The test holds a `WeakReference` to the
   runtime and waits for the garbage collector to free it. The lesson: a retained object keeps everything
   it points to alive, so keep only what the next reader needs (the report), not the machinery that made it.

## What breaks if...

...`Admission.release()` were a plain `if (!released) { released = true; giveBack(); }` without
compareAndSet? Run a start, let it finish and be expired at the same moment, and say what `active` could
become, and what the next visitor sees. Then say what breaks if `RunLimits.tryStart` read the active count
outside its lock. Which test class would catch each (`RunLimitsStressTest`)? Try it and explain the
failure.
