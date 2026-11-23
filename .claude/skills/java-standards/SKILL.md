---
name: java-standards
description: Java and Spring conventions for Settlement Lab - engine and api modules, package layout, concurrency rules, money, errors and the v2 API. Load before writing or reviewing Java code.
---

# Java standards (Settlement Lab)

SPEC.md is the source of truth. This skill is the short checklist.

## Modules and packages

- `engine`: plain Java library under `com.siddheshfulawade.settlementlab`: `domain`, `idempotency`,
  `pipeline`, `gl`, `resilience`, `runner`. Imports only `java.*` and the libraries in
  `docs/adr/0001-dependencies.md`. **Never Spring.** ArchUnit enforces this.
- `api` (v2): Spring Boot app under `...settlementlab.api`. It wraps the engine; the engine never
  knows about it.

## Java style

- Records for values, sealed interfaces for outcomes, small classes, clear names. No Lombok.
- Money is `long` minor units. Never `double`.
- Compile with `release 21` (JDK 21 and 25 both build it); no API newer than Java 21.
- `Optional` only as a return type. Constructor injection only in `api`.

## Concurrency rules

- Write the invariant in a comment next to the code that protects it.
- Lock accounts in ascending id order; reject a transfer to the same account.
- Never hold a lock across I/O or a blocking call. Release locks in `finally`.
- Bounded queues only, with defined behaviour when full.
- Check and record the idempotency key atomically with the effect.
- Timing that promises a minimum (latency, backoff) waits to a `System.nanoTime()` deadline:
  `sleep` and `parkNanos` can wake early, especially on Windows.
- Every external call has a timeout; retries use jitter; the circuit breaker state is observable.

## API (v2)

- Jakarta Validation on request records; reject unknown JSON fields; body size limits.
- Errors are RFC 9457 `ProblemDetail`, never stack traces or library messages.
- Rate limit every public endpoint; cap run sizes; only `health` is public on actuator.
- CORS allows only `https://siddheshfulawade.com`.

## Gate

`mvnw.cmd verify` (Windows) or `./mvnw verify`: compile, tests, JaCoCo ≥ 85 %, Spotless, ArchUnit.
Run `mvnw.cmd spotless:apply` to fix formatting. CI runs on Ubuntu and Windows, JDK 21 and 25.
