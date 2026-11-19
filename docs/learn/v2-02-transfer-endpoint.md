# v2-02 · POST /api/v1/transfers (V2-02)

## What it is, in plain words

```bash
curl -i -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -d '{"id":"t-1","source":1,"target":2,"amount":100}'
```

- `TransferRequest` is a record with Bean Validation (`@NotBlank id`, `@NotNull` numbers). A missing
  field or broken JSON is answered 400 with an RFC 9457 problem detail and never reaches the engine.
- The controller turns the request into a `Transfer`, asks the engine for the reason it would be
  invalid (if any), submits it, and maps the outcome when the future completes:
  `POSTED` 201, `REJECTED_FUNDS` 422, `REJECTED_INVALID` 400 with `reason`, `QUEUE_FULL` 503 with
  `Retry-After: 1`. The mapping lives in `TransferResponses`, one switch, unit tested.
- **Idempotency over HTTP comes for free:** the engine's `IdempotencyStore` answers a repeated id with
  the original outcome, so the API returns the same status. A real-socket test sends the same
  transfer from 20 clients at once: all get 201, the money moves once.
- After shutdown has begun, the engine refuses work; the controller answers 503 "Shutting down".

## Interview questions

1. **Why 503 with `Retry-After` for a full queue, not 429?**
   429 means "you, this client, sent too many". A full queue is the server being over capacity for
   everyone: that is 503. `Retry-After` tells well-behaved clients when to come back.
2. **Why is a retry after 503 safe?**
   The transfer id is the idempotency key, and a `QUEUE_FULL` claim is released, so the retry is
   processed normally, and a retry of anything already processed gets the original answer.
3. **Why validate the shape in the API but the business rules in the engine?**
   The engine is the one source of truth for what a valid transfer is, for every caller (HTTP, the
   runner, tests). The API only rejects what is not even a transfer.
4. **What does returning `CompletableFuture` from a controller do?**
   Spring MVC starts async processing: the request thread is released, and the response is written
   when the future completes. The engine's worker thread completes it.
5. **Why test over a real socket as well as with MockMvc?**
   MockMvc skips the servlet container and the network. The concurrency guarantee is about real
   clients racing, so one test sends real HTTP requests from 20 threads at once.
