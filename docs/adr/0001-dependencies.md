# ADR-0001: Dependencies

- Status: accepted
- Date: 2026-11-19

## Context

SPEC.md section 5 asks for exact version pins, the latest stable versions on Maven Central at creation,
and an entry here for every dependency. Fewer dependencies means less to defend and less to patch.

## Decision

### Runtime

| Dependency | Version | Why | Added in |
| ---------- | ------- | --- | -------- |
| none yet | | The domain, idempotency and (later) pipeline packages use only `java.*`. | L-01 |
| `io.github.resilience4j:resilience4j-retry`, `resilience4j-circuitbreaker` | 2.4.0 (pin when added) | Retry and circuit breaker around the general ledger (SPEC.md section 4.4). Not added until L-07, so that nothing unused ships. | L-07 (planned) |

### Test

| Dependency | Version | Why |
| ---------- | ------- | --- |
| `org.junit:junit-bom` (Jupiter) | 5.14.4 | Test framework. **Not 6.x:** jqwik 1.10.2 is built on JUnit Platform 1.14; mixing it with Platform 6 risks engine discovery failures. Revisit when jqwik supports Platform 6. |
| `org.assertj:assertj-core` | 3.27.7 | Readable assertions. 4.0.0 is still a milestone. |
| `net.jqwik:jqwik` | 1.10.2 | Property-based tests: random accounts and transfers, with shrinking to the smallest failing case. |
| `org.awaitility:awaitility` | 4.3.0 | Waiting for asynchronous results without `Thread.sleep`. |
| `com.tngtech.archunit:archunit-junit5` | 1.5.1 | Package rules (REQ-LAB-016) checked as tests. |

### Build

| Tool | Version | Why |
| ---- | ------- | --- |
| Maven (via wrapper 3.3.4, `only-script`) | 3.10.1 | Same Maven everywhere; `distributionSha256Sum` checks the download. `only-script` means no binary jar in git. |
| maven-compiler-plugin | 3.16.0 | `release 21`, `-Xlint:all -Werror`. |
| maven-surefire-plugin | 3.6.1 | Runs JUnit Platform (Jupiter, jqwik, ArchUnit engines). |
| maven-enforcer-plugin | 3.7.0 | Java and Maven versions, dependency convergence, every plugin pinned. |
| jacoco-maven-plugin | 0.8.16 | Line coverage gate of 85 %, `runner` excluded. |
| spotless-maven-plugin + palantir-java-format | 3.11.0 + 2.103.0 | One format, checked on `verify`; `./mvnw spotless:apply` fixes it. |
| clean, resources, jar, install, deploy, site plugins | 3.5.0, 3.5.0, 3.5.1, 3.2.0, 3.2.0, 3.22.0 | Pinned so the enforcer's `requirePluginVersions` passes and builds do not drift with Maven. |

## Consequences

- Adding a dependency means a row here and a reason. Upgrades are deliberate, one PR each.
- The enforcer allows JDK 21 or newer (`java.minimum.version`), because the code must compile on 21.
  CI builds on 21 and 25.
