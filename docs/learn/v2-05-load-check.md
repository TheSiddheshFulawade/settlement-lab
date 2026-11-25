# v2-05 · The HTTP load check (V2-05)

Source of truth: SPEC.md sections 11 and 18 (REQ-API-002, REQ-RUN-003, REQ-RUN-010, REQ-API-010) and
`docs/api-contract.md`. All files are in `api/src/test/java/com/siddheshfulawade/settlementlab/api/`.

## What it is, in plain words

A small program that acts like an outside customer. It knows only a web address. It sends many
transfers at the same time, reads the balances back, and then asks one question per rule: "did money
stay correct?" Each question has a name, such as `TOTAL_CONSERVED`. A line says `PASS` or `FAIL`, with
the numbers that decided it.

It has two parts.

1. **Transfers.** A seeded mix: normal transfers, over-the-top amounts, invalid ones, some sent by
   2 or 3 clients at the same moment, and some sent again later (`TransferPlan.java:36-55`,
   `TransferLoad.java:61-75`). 9 checks (`TransferChecks.java:27-38`).
2. **One run.** It starts a run through the runs API, reads the event stream to the end, and reads the
   report. 10 checks (`RunChecks.java:24-42`).

That is 19 checks in all. With `--runs-only` only part 2 runs, so only the 10 run checks.

It is a correctness check, not a benchmark. It never says how fast the server is.

## How it works

```
 HttpLoadCheckMain (arguments, refuses bad targets, exit code)
        |
 HttpLoadCheck.run  ------------------------------------------+
   |  part 1: sendTransfers                                    |  part 2: RunLoad.run
   |    ServerReads: opening balances + metrics (baseline)     |    POST /api/v1/runs  -> 202
   |    TransferPlan: the seeded mix                           |    GET .../events     -> read to the end
   |    TransferLoad: 32 virtual-thread clients                |    GET .../runs/{id}  -> the report again
   |    ServerReads: closing balances, wait for the GL         |
   |    TransferChecks.evaluate -> 9 results                   |    RunChecks.evaluate -> 10 results
   v                                                           v
        LoadHttp: the only door (java.net.http), counts every request by status and route
```

Step by step:

1. **One door.** `LoadHttp` is the only class that touches the network (`LoadHttp.java:23-30`). It counts
   requests by status and by route, so the report can say what was really sent. A test uses these counts
   to prove that "runs only" sends nothing but runs (`HttpLoadCheckRunsOnlyTest.java:38-44`).
2. **Baseline first.** `sendTransfers` reads every account balance and the metrics counters before the
   load (`HttpLoadCheck.java:84-89`). All later checks use differences from that baseline. So a server
   that was used before, or by another test, is fine (`ServerReads.java:16`).
3. **A seeded mix.** The plan depends only on the seed (`TransferPlan.java:8-10`), so a failure can be
   replayed. Ids contain a time tag, so a second run on the same server never reuses an id (`:34`).
   About 8 % are over-the-top (`422`), about 7 % invalid (`400`), 20 % are sent by 2 or 3 clients at once.
4. **Release together.** Copies of one transfer wait on a latch and start together
   (`TransferLoad.java:82-91`), so duplicates really race.
5. **A 503 stored nothing.** The client waits for `Retry-After` and sends the same id again, at most 5
   times (`TransferLoad.java:95-111`). This is safe because ids are idempotent.
6. **Let the general ledger catch up.** The general ledger is fed by the outbox, so it may trail. The
   check polls for at most 20 s until it has the expected entries and the outbox is empty
   (`HttpLoadCheck.java:107-122`).
7. **Judge.** Each check is a small method with its own name and its own message
   (`TransferChecks.java:15-23`).

## The checks

Transfers (`TransferChecks.java`), 9:

| Name | What it asks |
| ---- | ------------ |
| `ALL_ANSWERED` | every id ended with a decided answer (201, 422 or 400) |
| `EXPECTED_STATUS_PER_KIND` | over-the-top got 422, invalid got 400, normal got 201 or 422 |
| `TOTAL_CONSERVED` | the sum of all balances is the same before and after |
| `NO_NEGATIVE_BALANCE` | no account is below zero |
| `BALANCES_MATCH_OPENING_PLUS_201S` | each balance equals opening plus the net of the distinct transfers answered 201 |
| `REPEATS_GET_ORIGINAL_STATUS` | every repeat of an id got the first status and outcome |
| `METRICS_AGREE_WITH_CLIENT` | the server's counters (as differences) match what the client counted |
| `GL_ONE_ENTRY_PER_POSTING` | the general ledger gained exactly one entry per posting, outbox empty |
| `MIX_PRODUCED` | the load really produced a posted, a rejected-for-funds and an invalid transfer, and a request sent more than once |

Run (`RunChecks.java`), 10: `RUN_ACCEPTED`, `RUN_ONE_REPORT_AND_IT_IS_LAST`,
`RUN_STREAM_CLOSES_AFTER_REPORT`, `RUN_HAS_A_METRICS_EVENT`, `RUN_ALL_SIX_INVARIANTS_PASSED`,
`RUN_OPENING_TOTAL_EQUALS_CLOSING_TOTAL`, `RUN_NO_DUPLICATE_APPLIED`, `RUN_COMPLETED_EVERY_TRANSFER`,
`RUN_GET_RETURNS_THE_SAME_REPORT`, `RUN_REPORT_IS_FOR_THIS_REQUEST` (the report has the `runId` of the 202
and the settings that were sent, `RunChecks.java:167-182`).

## The core ideas

### 1. A check that cannot fail proves nothing

`HttpLoadCheckDetectsFailuresTest.java:12-15` says it: start from data where every check passes, damage
it the way a real bug would, and expect exactly the named check to turn red. For example, a credit without a
debit must turn `TOTAL_CONSERVED` and the balance check red (`:82-89`), and a duplicate applied twice must
turn only `BALANCES_MATCH_OPENING_PLUS_201S` red (`:99-105`). That test uses made-up data. The three
mutation proofs below do the same with a real broken engine.

### 2. The client has its own expected answer

`balancesMatch201s` (`TransferChecks.java:144`) does not ask the server what it thinks. It adds up,
by itself, opening balance plus the net of each distinct transfer that got a 201. Then it compares. A
checker that copies the server's own numbers can agree with a wrong server.

### 3. Differences, not absolute numbers

Counters since the server started are read before and after, and only the difference is used
(`metricsAgree`, `TransferChecks.java:197`). That is why a used server is fine.

### 4. Safety on the target

Without `--runs-only` the target must be loopback (`requireAllowedTarget`, `HttpLoadCheckMain.java:117-138`,
`isLoopback` `:140-144`). Names are matched as text and never resolved, so a name that merely points to
127.0.0.1 is not trusted. A URL with a user name or password is refused and never printed
(`:135-137`). Production serves only runs and limits them (REQ-API-010, 5 per visitor per hour), so
`--runs-only` does one run and nothing else. Size options are checked: `--operations` and `--clients` at
least 1, `--run-transfers` 1 to 20,000 (`:95-100`). Exit codes: 0 all passed, 1 a check failed or the
check could not finish, 2 refused or bad arguments (`:21`, `:64`, `:73`, `:86`).

### 5. Inside `verify`, four times

`HttpLoadCheckContract` is run by four small classes: Locking and CAS, each with platform and virtual
threads (`HttpLoadCheckLockingVirtualTest.java:8-17`). Each starts the real server on a random port. Then
the contract also looks inside the server with the engine's own `InvariantChecker`
(`HttpLoadCheckContract.java:59-75`): the outside view and the inside view must agree.

### 6. The client does not trust the server to behave

A load check that can be hung by a bad server is itself a bug. So:

- **No proxy, no redirects.** The machine's proxy setting is ignored, and a 307 is an answer, not an
  instruction (`LoadHttp.java:59-60`).
- **Every wait ends.** The answer must arrive within 30 s (`:49`, used at `:145`). The body is read
  under a watchdog and is cut at 1,000,000 bytes (`:51`); an event stream may be twice that (`:118`). All
  of this is in `fetch` (`:139-181`). The event stream has its own 90 s limit (`RunLoad.java:21`).
- **One shared deadline.** The transfer load has one 120 s deadline for both phases, not one per thread
  (`TransferLoad.java:39-40`). When it passes, `stopped` is set and the clients are interrupted, so none
  keeps sending (`:97`, `:113-143`).
- **An empty report never passes.** `Report.passed()` needs at least one check (`HttpLoadCheck.java:49-52`).
- **Proof.** `MisbehavingServer` is a raw loopback server with three bad manners: `SILENT` (never answers),
  `DRIP` (one byte at a time, forever) and `FLOOD` (a flood of data). `HttpLoadCheckLimitsTest` shows each
  limit holds, that redirects and proxies are not used, and that clients stop after the deadline.

### 7. A check that passes on nothing (an audit finding)

An audit found that the checks first passed **vacuously**: they said PASS even when they had nothing to
judge. A load with no transfers, or a server that answered every normal transfer 422, moved no money, so
the totals, balances and counters all agreed with each other and every line was green. A pass that would be
the same for a working and a broken system is not evidence. `MIX_PRODUCED` closes this
(`TransferChecks.java:44-67`): it turns red unless the load really produced a posted, a rejected-for-funds
and an invalid transfer, and a request sent more than once. The tests are
`HttpLoadCheckDetectsFailuresTest.java:179-199` (every normal transfer answered 422 turns only
`MIX_PRODUCED` red) and `:249-257` (an empty load fails instead of passing). The same audit made
`--operations 0` a refusal (exit 2) and an empty list of checks a failure
(`HttpLoadCheck.java:49-52`, `HttpLoadCheckMain.java:95-100`). A very small `--operations` (a
handful of transfers) can turn `MIX_PRODUCED` red because the seeded mix may not contain every kind or any
repeat. That is correct: such a load proves little. The message says so ("use more").

## The three mutation proofs

A mutation proof means: break the real code on purpose, run the check, and see it go red. The results
below are from the V2-05 build session (2026-11-24); each change was undone, so the broken code is not in
the repository. To explain them in an interview, repeat one yourself first.

1. **`LockingLedger` without `source.balance -= amount`.** The credit still happens, the debit does not,
   so money is created. Red: `TOTAL_CONSERVED`, `BALANCES_MATCH_OPENING_PLUS_201S`,
   `RUN_ALL_SIX_INVARIANTS_PASSED`. 5 tests red.
2. **`IdempotencyStore.claim` using `put` instead of `putIfAbsent`.** The second caller with the same id
   replaces the first caller's claim and is treated as the owner, so a duplicate is applied again. Red:
   `BALANCES_MATCH_OPENING_PLUS_201S`, `METRICS_AGREE_WITH_CLIENT`, `RUN_ALL_SIX_INVARIANTS_PASSED`,
   `RUN_NO_DUPLICATE_APPLIED`. 10 tests red. **`TOTAL_CONSERVED` rightly stays green.** A duplicate
   applied twice moves the same amount a second time: one account loses it, another gains it. Money moved
   too often, but none was created or lost, so the total is the same. This is the reason there are many
   checks: one total cannot see this bug, the per-account check can.
3. **`OutboxPublisher` drops every 10th posting.** The ledger is right, so balances and totals are fine.
   Only the general ledger is short. Red: `GL_ONE_ENTRY_PER_POSTING` and
   `RUN_ALL_SIX_INVARIANTS_PASSED` (its `GL_MATCHES_POSTINGS` invariant). 10 tests red.

Reading them together: each bug turned red the checks that watch that part, and the total-money check
stayed green when no money was created. That is the evidence the checks are sharp.

## TODO-ME

`HttpLoadCheckTodoTest.java:28-39` has `sameIdWithDifferentAmount`, marked
`@Disabled("TODO-ME ...")`. Write it with `new LoadHttp("http://localhost:" + port)`:

- Read `GET /api/v1/accounts/1` first.
- `POST /api/v1/transfers` twice with the same id (`todo-me-1`), account 1 to 2, amounts 10 and 99.
- Assert both answers are `201` with outcome `POSTED`, and that account 1 dropped by 10 only.

**Why it has its own class.** It runs on its own server (its own settings, so its own Spring context:
`HttpLoadCheckTodoTest.java:14-22`, 10 accounts of 5,000). Before, it sat in the shared server of
`HttpLoadCheckLockingVirtualTest`. A correct solution posts a transfer that the contract's
`InvariantChecker` does not know about (`HttpLoadCheckContract.java:59-75` is given only the ids the check
itself sent), so a right answer would turn that other test red. In its own context, nothing else can be
disturbed.

Check: remove `@Disabled` and run
`mvnw.cmd -q -pl api -am test -Dtest=HttpLoadCheckTodoTest -Dsurefire.failIfNoSpecifiedTests=false`.
(`-am` also builds the engine, which has no such test; the last flag stops Maven failing there.) Then ask
yourself which engine line would make the 99 apply, and why your test would catch it. Not filled in here.

## Interview questions

1. **Why check the system over HTTP when the engine already has property and stress tests?**
   Those tests prove the engine alone. A real server adds a port, real threads, JSON, status codes,
   `Retry-After`, and the event stream, and a bug can hide in any of them (for example a wrong status for
   a repeat). A black-box client sees what a customer sees. The two are complementary: the contract class
   also runs the engine's own invariant checker over the same server, so the outside and inside views must
   agree.

2. **How do you know your checks would really fail if the system were wrong?**
   The real code was broken on purpose three ways, and the right checks went red: no debit
   (`TOTAL_CONSERVED`), `put` instead of `putIfAbsent` (`BALANCES_MATCH_OPENING_PLUS_201S`,
   `RUN_NO_DUPLICATE_APPLIED`), and a dropped general ledger posting (`GL_ONE_ENTRY_PER_POSTING`). There are
   also unit tests that damage an observation and expect exactly one named check to fail
   (`HttpLoadCheckDetectsFailuresTest`). A check that has never been seen failing is only a hope. An audit
   also found that the checks first passed vacuously: on an empty load, or a server that refused every
   normal transfer, every check was green because there was nothing to judge. `MIX_PRODUCED` closes that
   by failing unless the load really produced the whole mix.

3. **A duplicate was applied twice, yet `TOTAL_CONSERVED` is green. Why, and what catches it?**
   Applying a transfer twice moves the money twice: one account loses it, another gains it. The sum is
   unchanged. The total only catches money created or lost. `BALANCES_MATCH_OPENING_PLUS_201S` catches it
   because the client computed each balance itself from the transfers it saw answered 201, and
   `METRICS_AGREE_WITH_CLIENT` catches it because the engine's `posted` count is higher than the client's.

4. **Why use differences from a baseline instead of expecting exact counters?**
   The server may have been used already: by earlier tests in the same Spring context, or by someone
   running the check twice. Absolute counters would then be wrong for reasons that are not bugs. Reading
   balances and counters before the load, and comparing the change, tests only what this load did. Ids carry
   a time tag for the same reason, so a second run never reuses an id.

5. **Why does the check refuse non-loopback hosts, and why is it not a benchmark?**
   The load sends thousands of transfers. Pointed at a server you do not own, that is an attack, and the
   public service serves only runs and limits them (REQ-API-010, REQ-RUN-006). So the default needs a
   loopback address, matched as literal text with no DNS lookup, and `--runs-only` sends one run and
   nothing else. It is not a benchmark because it measures nothing about speed: the clients are
   closed-loop, the mix includes waits for `Retry-After`, and it ends with a bounded poll. A pass means
   "correct", never "fast". Speed is measured by the runner (results above in the README).

## What breaks if...

...`TransferLoad` did not wait for `Retry-After` and did not reuse the same id after a 503? Think about
what `ALL_ANSWERED` and `METRICS_AGREE_WITH_CLIENT` would say, and which engine rule makes the retry with
the same id safe (README, Idempotency L-04: unprocessed claims are released). Then say what breaks if the
check used a fresh id for each retry: which check would catch a transfer applied twice under two ids, and
which would not? Try it on a copy, and explain what you see.
