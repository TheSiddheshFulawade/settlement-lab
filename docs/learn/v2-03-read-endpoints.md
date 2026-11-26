# v2-03 · Balance and metrics endpoints (V2-03)

## What it is, in plain words

```bash
curl localhost:8080/api/v1/accounts/3     # {"id":3,"balance":500}
curl localhost:8080/api/v1/metrics        # settings, engine, outbox, breaker, generalLedger
```

- **`Ledger.balance(AccountId)`** is new in the engine (framework-free, like everything there).
  `LockingLedger` takes only that account's lock, so it never sees a transfer half-way through that
  account and never pauses transfers on other accounts. `CasLedger` reads the current immutable
  state once, with no lock at all. Unknown id: `Optional.empty()`, which the API turns into 404.
- **Proven** by a jqwik property (for any transfers, `balance(id)` equals the snapshot's entry) and
  a stress test (a reader polling balances while 8 threads transfer never sees a negative or
  impossible value).
- **`GET /api/v1/metrics`** collects what the runner's table shows, live: engine counters, outbox
  size and peak, breaker state, opens and retries, GL entries and duplicate publishes.
- Build note: Spring binds `@PathVariable long id` by the parameter name, which needs `javac
  -parameters`; `spring-boot-starter-parent` turns it on, so with our own parent we set it ourselves.

## Interview questions

1. **Why add `balance(id)` instead of reading from `snapshot()`?**
   On the locking ledger a snapshot locks every account, pausing all transfers, to answer a question
   about one. Reading one account needs one lock; cost no longer grows with the number of accounts.
2. **Is a single-account read "consistent"?**
   It is consistent for that account: no half transfer on it is visible. It says nothing about a
   moment shared with other accounts; for that you need the snapshot.
3. **Why 404 with a problem detail for an unknown account, and 400 for `/accounts/abc`?**
   `abc` is not even a valid id (a malformed request: 400). `99` is a valid id that names nothing
   (404). Both use RFC 9457 bodies so clients parse errors one way.
4. **Why not Spring Boot Actuator for metrics yet?**
   A handful of numbers do not need a framework and a new dependency. If the app were ever deployed and
   scraped by a monitoring system, Actuator with Micrometer is the standard choice.
5. **What does `-parameters` do and why did the endpoint fail without it?**
   It keeps method parameter names in the class file. Spring reads them to match `{id}` to `long id`.
   Without it, Spring cannot tell which parameter `{id}` belongs to and refuses the request.
