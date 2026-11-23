# v3-02 · One set of ledger tests for every ledger (V3-02)

## What it is, in plain words

Until now the ledger property and stress tests lived in the `engine` module and ran against
`LockingLedger` and `CasLedger`. V3-03 adds a third ledger, `PostgresLedger`, in another module
(`ledger-postgres`). We want it to pass exactly the same tests, without copying them. V3-02 makes
that possible and changes no behaviour.

**What changed:**

- The two suites became **abstract classes**: `LedgerPropertiesContract`
  (`LedgerPropertiesContract.java:26`) and `LedgerStressContract` (`LedgerStressContract.java:26`).
  Each has one abstract method, `newLedger(Map<AccountId, Money>)` (lines 29 and 33), meaning "build
  me a fresh ledger with these balances". All the proofs stay in the abstract class.
- Each ledger gets a tiny concrete subclass that only answers that question, for example
  `CasLedgerStressTest`: `return new CasLedger(openingBalances);`. There are four in the engine
  (Locking and Cas, each for properties and for stress).
- The enum `LedgerKind` was deleted. It used to list the ledgers; now a new ledger is a new subclass.
- The engine builds a **test-jar** (`engine/pom.xml:92-104`, `maven-jar-plugin`, goal `test-jar`): a
  second jar holding the engine's *test* classes (the contracts, `Fixtures`, `Concurrently`).
- `ledger-postgres` depends on it (`ledger-postgres/pom.xml:58-65`, `<type>test-jar</type>`,
  `<scope>test</scope>`) and has two wiring tests, `SharedSuitesWiringTest` and
  `SharedStressWiringTest`. They extend the contracts with the in-memory `LockingLedger`, so they need
  no Docker. They only prove the test-jar can be used from this module. V3-03 swaps in `PostgresLedger`.

**Why an enum could not be shared.** `LedgerKind` listed every ledger in one place:
`LOCKING(LockingLedger::new)`, `CAS(CasLedger::new)`. An enum is closed: its constants are fixed
where it is written, and no other module can add `POSTGRES`. `PostgresLedger` lives in
`ledger-postgres`, which depends on the engine, so the engine cannot name it (that would be a cycle,
and `ArchitectureTest` forbids it). With an abstract class the direction flips: the engine only says
"here is the contract", and each module that owns a ledger writes its own subclass. The extension
point is open, not a fixed list.

**What a Maven test-jar is.** Normally a module's jar holds `src/main` only, so another module cannot
see its test classes. The `test-jar` goal packages `src/test` into a second jar with the classifier
`tests`. Another module asks for it with `<type>test-jar</type>`. We use `test` scope so the test
classes never leak into production code.

**Why test-jar dependencies are not transitive.** The engine's jqwik, AssertJ and Awaitility are
`test` scope there, and Maven does not pass test-scoped dependencies on to modules that depend on
it. So `ledger-postgres` gets the contract classes but not the libraries they call. Without them the
contract fails to compile or load (`NoClassDefFoundError`). That is why `ledger-postgres/pom.xml:66-76`
declares `jqwik` and `awaitility` again, with the same version properties. Each dependency also needs
its entry in `docs/adr/0001-dependencies.md`, as the project rule says.

**How jqwik and JUnit run inherited tests.** Both engines look at methods inherited from
superclasses. JUnit Jupiter runs the `@Test` methods of `LedgerStressContract` once for every
concrete subclass, with `this` being that subclass, so the abstract `newLedger` call reaches the
right ledger. jqwik does the same for `@Property` methods and finds the `@Provide` method
`scenarios()` (line 37) in the superclass. The abstract class itself is skipped (it cannot be
created). Two details matter: the contract and its test methods are `public` so another module can
use them, and the subclasses are named `*Test` so Surefire picks them up, while the `*Contract`
names do not match and never run alone.

```
engine module                                 ledger-postgres module
-------------                                 ----------------------
LedgerPropertiesContract (abstract)
LedgerStressContract     (abstract)
Fixtures, Concurrently
   |            |
   |            +-- test-jar ("tests") ------> test-scope dependency
   |                                           + jqwik, awaitility declared again
   v                                                  |
LockingLedgerPropertiesTest -> LockingLedger          v
CasLedgerPropertiesTest     -> CasLedger       SharedSuitesWiringTest -> LockingLedger (for now)
LockingLedgerStressTest     -> LockingLedger   SharedStressWiringTest -> LockingLedger (for now)
CasLedgerStressTest         -> CasLedger       PostgresLedger tests   (V3-03)
```

**Oracle note.** None needed: this task is test structure and has no SQL. The database side starts
in V3-03. (The pattern is database-neutral: an Oracle-backed ledger would just be one more subclass.)

## The TODO-ME for you

In `LedgerPropertiesContract.java:169-176`, write `wholeBalanceTransferEmptiesTheSource()`.

**What it should prove:** moving a source's entire balance is allowed. Open two accounts (for
example `Fixtures.uniformAccounts(2, n)`), transfer the whole balance of one to the other, and assert
the outcome is `POSTED` and the source is exactly `Money.of(0)`.

**Why it matters:** it pins the edge of the funds rule (the balance must be at least the amount, not
strictly more). Off-by-one errors on a boundary are the classic bug, and random properties rarely hit
the exact boundary.

**Hints:**
- Build the ledger with `newLedger(...)`, never `new LockingLedger(...)`, so the same test runs on
  every ledger. The stub uses `@Property(tries = 1)` and has no parameters; keep the method name.
- Use `Fixtures.transfer(id, source, target, amount)` as the other tests do.
- Also check the target's balance rose by the same amount.

**Run it:** `./mvnw -pl engine verify`. Then check it can fail: change the funds test in
`LockingLedger` from `<` to `<=` (as the stub says), see it go red, and restore it.

## Interview questions

1. **What is a contract test and why use one here?**
   A test written once against an interface, that every implementation must pass. The abstract class
   holds the proofs (money conserved, no negative balance, no deadlock) and each ledger supplies only
   a factory. Whatever the storage, memory or PostgreSQL, a ledger is judged by the same rules, and a
   new ledger cannot quietly skip a proof.
2. **Why did the `LedgerKind` enum have to go?**
   Enum constants are fixed in one file. `PostgresLedger` lives in a module that depends on the
   engine, so the engine cannot reference it without a dependency cycle. An abstract class lets each
   module add its own subclass, and the engine never needs to know who extends it.
3. **What is a Maven test-jar, and why are its dependencies not transitive?**
   A second jar with a module's test classes, used with `<type>test-jar</type>` and test scope.
   Test-scoped dependencies are not inherited by consumers, so the consuming module must declare
   jqwik, AssertJ and Awaitility itself, or the borrowed classes fail to compile or load.
4. **How do jqwik and JUnit run a test method defined in a parent class?**
   They include inherited annotated methods when they scan each concrete class. `@Test` and
   `@Property` methods run once per subclass, so the abstract `newLedger` goes to that subclass's
   ledger. The abstract class is never run alone. jqwik also finds `@Provide` methods in the parent.
5. **What alternatives did you reject?**
   Copying the suites: they drift apart and a fix lands in one place only. A `testFixtures` module:
   `java-test-fixtures` is a Gradle feature, so in Maven it would be a new module just to hold test
   code. A `ServiceLoader` or registry: more machinery, and the same closed-list problem as the enum.
   Abstract classes plus a test-jar use a standard Maven feature and the least code.

## What breaks if...

You delete the `jqwik` dependency from `ledger-postgres/pom.xml:66-70` but keep the test-jar. Predict
what happens and at which step (compile, test discovery, or run). Then try it in a scratch copy: does
`./mvnw -pl ledger-postgres -am verify` fail, and with what message? Put it back, then make
`LedgerPropertiesContract` package-private instead. What fails, and in which module?
