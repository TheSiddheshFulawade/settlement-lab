---
name: testing-playbook
description: How Settlement Lab tests are written - test-first, naming, invariant properties, concurrency stress and deadlock tests, timing tests and API slices. Load before writing or reviewing tests.
---

# Testing playbook (Settlement Lab)

## Rules

- Test first: write the test, see it fail for the right reason, then write the code.
- A bug fix starts with a test that reproduces the bug.
- Name tests after behaviour and the REQ id from SPEC.md, e.g. `REQ-008 duplicate delivery posts once`.
- One behaviour per test; arrange, act, assert separated by blank lines. JUnit 5 + AssertJ.

## Invariants (the heart of the project)

For N random transfers run by K threads, always assert:

- the sum of all balances is unchanged;
- no transfer id is posted twice;
- no balance goes below zero.

Use jqwik properties for random inputs, and keep shrinking working so a failure shows a small case.

## Concurrency patterns

- Stress: start threads behind a `CountDownLatch`, release them together, join with a timeout,
  then check the invariants.
- Deadlock: run opposing transfers (A→B and B→A) together under a timeout.
- Async completion: Awaitility. Never `Thread.sleep` to synchronise.
- Timing: assert lower bounds only where the code promises a minimum, and upper bounds with a
  generous margin. CI runs on Windows too, so a test must pass on coarse timers.

## API tests (v2)

`@WebMvcTest` slices for controllers, one boot test for wiring, problem details checked on every
error path, and an HTTP load check that verifies the invariants at the end.
