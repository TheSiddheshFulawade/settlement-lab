# Settlement Lab

A concurrency-safe settlement engine: move money between accounts concurrently without ever creating
or losing any, under duplicates, downstream failures and overload. Then measure locking and
threading choices. v1 is a plain Java library with a runner; v2 wraps it in a Spring Boot API at
`api.siddheshfulawade.com`. Write-up: https://siddheshfulawade.com/projects/settlement-lab

**SPEC.md is the source of truth** (requirements, design, tasks). Read it before any work.

## Rules

- **Original work only.** Nothing from any employer: no designs, names, code, message formats,
  system names or volumes. Generic payments terms only (account, transfer, posting, ledger).
- **Java:** target Java 25 (dev and CI), and the code must also compile on JDK 21: use no language
  feature or API newer than Java 21. Money is `long` minor units, never `double`. This refines SPEC.md §5: compile with `maven.compiler.release` 21
  so JDK 21 and 25 both build it; run and benchmark on Java 25.
- **No Spring in the engine.** The domain imports only `java.*`; ArchUnit enforces the package rules.
- **Tests first** for every task: unit tests, jqwik properties, concurrency stress tests that check
  invariants. Awaitility for async; never `Thread.sleep` to synchronise. JaCoCo line coverage ≥ 85 %.
- **Simple, readable code** over clever code: small classes, records, clear names, the invariant written
  next to the code that protects it. No new dependency without an entry in `docs/adr/0001-dependencies.md`.
- Never read or write secrets (`.env*`, keys, certificates). Never use `--no-verify` or force push.

## Build order (SPEC.md §7)

L-01 build skeleton · L-02 LockingLedger + invariant checker + property and stress suites ·
L-03 CasLedger · L-04 IdempotencyStore · L-05 intake, worker pool, engine facade ·
L-06 fake GL, faults, outbox · L-07 retry and circuit breaker · L-08 runner and results ·
L-09 README and a committed results run. Tick each task in SPEC.md when done.

## Branches and PRs

- **Never push to `main`**; all work arrives by PR. `main` is protected and requires the four
  `verify (os, jdk)` checks (Ubuntu and Windows × JDK 21 and 25).
- One branch and one PR per task, from `main`: `feature/v3-NN-<slug>`, `feature/v2-NN-<slug>`,
  `fix/<topic>`, `chore/<topic>` or `docs/<topic>`. Tasks run back to back; a session may ship several.
- **`git-ops` owns all pushes, PRs and merges** (`/ship` delegates to it): squash-merge on the
  required green checks, no user yes needed. Red CI is reported, never bypassed.
- **Hard gates** (stop, write it under "Waiting on user" in the handoff, move to the next unblocked
  task, never work around it): money or cloud resources (Google Cloud, Neon) · DNS and secrets ·
  GitHub repo settings · deploying · anything from an employer · a product choice no approved spec covers.

## Defend every line (the user must be able to explain all of it in an interview)

- **README "Decisions" entry per module:** what was chosen, the alternative, and why.
- **Study notes:** `docs/learn/NN-module.md` per module (NN = task number), explaining the code in plain
  words, with **5 interview questions and model answers** each.
- **One TODO-ME task per module:** a small, real piece (a test, a method or an edge case) left for the
  user to write, marked `// TODO-ME:` with what to do and how to check it. Never fill it in.
- **End of each day:** give the user a **5-question quiz** on that day's code, and record the questions
  in the day's handoff.

## Commands

`./mvnw verify` (Windows: `mvnw.cmd verify`) is the single gate: compile, tests, coverage, format,
architecture rules. Run the results with the runner described in SPEC.md §4.6.

## Claude setup (kept small on purpose)

- Hooks: `session-start` (prints SPEC.md task status and the latest handoff into every new session),
  `guard-bash` and `protect-secrets` (safety). Tests: `node --test .claude/hooks/*.test.mjs`.
- Skills: `java-standards` and `testing-playbook` (load before Java or test work), `/ship`,
  `/handoff`, `/quiz [module]`.
- Agents (each runs in its own context and reports back):
  - `test-engineer`: failing tests first, for every task.
  - `java-engineer`: makes them pass (engine and api).
  - `database-engineer`: PostgreSQL schema, SQL, locking and outbox (ADR-0002).
  - `deploy-engineer`: container and Cloud Run/Neon setup; never deploys or spends without a yes.
  - `security-auditor`: before any deploy and after changes to `api`, pom files or CI.
  - `tutor`: `docs/learn` notes, plain explanations, interview answers, TODO-ME reviews.
  - `git-ops`: branch, commit, push, PR, wait for the required checks, squash-merge on green.
- Typical task: `test-engineer` → `java-engineer` (or `database-engineer`) → `mvnw verify` (plus
  `-Pdb` for database work) → `security-auditor` for `api`, pom or CI changes → `tutor` writes the
  learn note and README Decisions entry → `/ship` (git-ops) → handoff line → next task.
- Database and hosting are decided in `docs/adr/0002-database-and-hosting.md`: PostgreSQL (Neon) and
  Cloud Run, both in Singapore, free tiers only.

## Handoffs

Keep the day's `docs/sessions/YYYY-MM-DD-NN-topic.md` (at most 20 lines) up to date after each merged
task: done (PRs and SHAs), state, next, waiting on user, open TODO-ME stubs, and each task's quiz.
