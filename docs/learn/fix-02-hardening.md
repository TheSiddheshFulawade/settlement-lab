# fix-02 · Hardening before the deploy

Nine findings from the security audits of V2-05 and V2-07, and two more (H-1, M-1) that the re-check of
this change found, fixed before anything is public. Each has a test that failed first, except the stalled-body test (N-3): it passes before and after because it sets its
own short timeout, so it guards the behaviour, and `TomcatTimeoutsTest` is the one that failed.

| Finding | What could happen | Fix |
| --- | --- | --- |
| L-3 | 100 submits × 100 ms each ran on up to 10 s past the deadline or a stop | check both before every submit in the burst |
| L-6 | an unknown path in prod showed the framework's error page | `ProdAllowListFilter` writes a fixed `not-found` problem |
| L-8 | `preview-origin: null` or `*` would be accepted; a trailing slash silently never matched | one exact https origin, or the application does not start |
| L-10 | each unknown path under `/runs/` wrote a WARN with the visitor's text | the allow-list matches three exact path shapes; that logger is at ERROR |
| L-11 | a start racing `close()` hit a stopped timer: 500 | it becomes `ServiceClosedException`: 503 with `Retry-After` |
| L-12 | on a detected cloud platform Tomcat rewrites the visitor's address | `forward-headers-strategy: none` |
| N-1 | one `Error` in a timer tick cancelled the timer for good | the ticks catch `Throwable` |
| N-2 | if logging threw, a failed run was not ended | `end()` first, then the log line |
| N-3 | a stalled small body held a connection for 60 s | `connection-timeout: 5s`, `keep-alive-timeout: 60s` (measured: answered at 5.0 s, closed at 10.0 s) |
| H-1 | a multipart body on GET or OPTIONS was parsed into temporary files, past the 16 KB cap | multipart parsing off |
| M-1 | a multipart type without a boundary was a 500 with a stack trace in the log | the same line: now 415 |

## How to test a race without sleeping (L-3)

`RunDriverBurstStopTest` makes the fake engine's `submit` wait on a semaphore from submit 31 on. The driver
is then parked inside a burst at a known count. The test moves the clock (or ends the run), opens the gate
and counts the submits that still follow: before the fix 72 or 73, after it at most the one in flight.

## What is not fixed here

- **L-4:** an ended run frees its slot before its engine has closed. REQ-RUN-005 asks for the early
  release; how many engines can pile up is measured at V2-06.
- **L-7:** Tomcat's own 400 page, and the framework's bodies for `TRACE` and unsupported methods. No version
  and no stack trace is shown.
- **A slow drip:** the timeout is per read, so one byte every 4 s keeps a connection open.

## Interview questions

1. **Why does a scheduled task need to catch `Throwable`?**
   `scheduleWithFixedDelay` cancels a task that throws, silently. The exception sits in a future nobody
   reads. The timer forgets visitor addresses, so it must survive. The price is that an
   `OutOfMemoryError` is caught too, so the JVM flag `-XX:+ExitOnOutOfMemoryError` has to end the process.
2. **Why check the deadline before every submit, and not once per burst?**
   A submit is not free: on a full queue it waits for the offer timeout (100 ms). 100 of those are 10 s.
   A check per submit bounds the overrun by one submit, and reading the clock costs almost nothing.
3. **Why is trusting `X-Forwarded-For` by default dangerous for a rate limit?**
   The visitor can send that header. Only the entries our own proxies appended are true, so the walk goes
   from the right. If Tomcat consumes the last entry first, the last one left is the visitor's forgery.
4. **What does `connection-timeout` in Tomcat protect against, and what not?**
   It is a limit per read. A client that stops sending is answered after 5 s and closed after about 10 s. A client that sends a byte
   every 4 s never hits it; that needs a total limit, here the platform's request timeout.
5. **Why an allow-list of exact path shapes rather than a prefix?**
   A prefix also lets through everything that merely begins the same way, so those requests reach the
   framework, its logs and its error pages. With exact shapes, anything new is refused until someone adds it.
