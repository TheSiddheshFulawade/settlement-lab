# v2-01 · Two modules, and the engine inside Spring Boot (V2-01)

## What it is, in plain words

- The build is now a Maven **parent** (`pom.xml`, packaging `pom`) with two **modules**: `engine`
  (all of v1, untouched apart from moving into `engine/`) and `api` (Spring Boot 4.1). The parent
  holds plugin versions and the shared gates; `./mvnw verify` at the root still checks everything.
- The engine module does not have Spring on its classpath at all, so "the engine never imports
  Spring" (REQ-API-007) is enforced by the compiler, on top of the ArchUnit rule.
- `SettlementProperties` is a record bound from `settlement.*` (YAML, command line or environment).
  Bad values fail at start-up, not on the first request.
- `SettlementRuntime` builds ledger → general ledger → retry and breaker → outbox → engine, and
  closes them in order: engine first (stop taking work, drain, wait for workers), then drain the
  outbox so the general ledger catches up, then close the outbox. It is plain Java, so that order
  is tested without Spring. `EngineConfiguration` makes it one bean with `destroyMethod = "close"`.
- `server.shutdown: graceful` lets in-flight HTTP requests finish before Spring closes beans, so
  the engine is closed only after the last request has its answer.

## Interview questions

1. **Why split into modules instead of keeping Spring out with ArchUnit alone?**
   A rule can be switched off or misconfigured; a missing dependency cannot be imported at all. It
   also lets each module have its own test stack (JUnit 5.14 with jqwik, JUnit 6 with Spring Boot).
2. **Why import the Spring Boot BOM instead of using `spring-boot-starter-parent`?**
   A project has one parent. Ours carries the enforcer, Spotless and JaCoCo gates for both modules;
   the BOM gives Boot's version management without taking over the build.
3. **What does graceful shutdown order look like here, and why does it matter?**
   Stop accepting HTTP requests, let in-flight ones finish, then close the engine (drain), then
   drain and close the outbox. Closing the outbox first would strand postings; closing the engine
   while requests are in flight would answer them with errors.
4. **Why validate configuration in the record's constructor?**
   Fail fast: a typo in `settlement.accounts` should stop the app at start-up with a clear message,
   not surface as a strange error under load.
5. **Why is `SettlementRuntime` plain Java rather than a set of Spring beans?**
   The wiring and the stop order are the interesting part; in one class they are explicit and unit
   tested. Spring only decides when to call `start` and `close`.
