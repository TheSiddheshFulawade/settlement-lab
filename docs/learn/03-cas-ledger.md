# 03 · CasLedger (L-03)

## What it is, in plain words

A second ledger with **no locks at all** (REQ-LAB-007). Every balance lives in one immutable `State`
(a `long[]` plus the posting count) held in an `AtomicReference`.

A transfer: (1) read the current state; (2) if the source is short, reject (that state was the truth
at that moment); (3) copy the array, debit, credit; (4) `compareAndSet(current, next)`. The swap
succeeds only if nobody published in between. If someone did, nothing of ours is visible: count a
**conflict** and go back to (1) with the fresh state.

- **Atomic across two accounts** because both changes are in one new object that is published in one
  step. A reader sees the old state or the new one, never half.
- **Snapshot is free:** one `state.get()` is already consistent. No pause, unlike `LockingLedger`.
- **Cost:** each write copies the whole array (10,000 accounts = 80 KB per transfer), and under
  contention many swaps fail and redo that work. `conflicts()` counts it; the runner (L-08) will
  measure it. CAS losing at high contention is a valid result.
- **Deadlock:** impossible, there is nothing to wait on. But it is not *wait-free*: a single unlucky
  transfer can in theory retry for a long time (starvation) while others keep winning.
- `OpeningBalances.checked` now holds the opening rules (no negatives, total fits in a `long`) for both
  ledgers, so they cannot drift apart.

**What the mutation check taught us:** replacing `compareAndSet` with a blind `set` loses whole
transfers, but every state is still a full copy, so the **total is conserved** and total-only tests
pass. Only replaying the postings over the opening balances catches it. The deadlock stress tests now
run the full `InvariantChecker`, and 3 of 4 fail on that mutation.

## Interview questions

1. **How is a CAS update atomic across two accounts without a lock?**
   Both balance changes go into one new immutable object; `compareAndSet` publishes it in a single
   atomic reference swap. Readers only ever dereference whole states, so no half transfer is visible.
2. **Why must the `long[]` never be written after the state is published?**
   Another thread may be reading or copying it right now without any lock. Mutating it would be a
   data race and could show a debit without its credit. Immutability after publication is the
   safety argument; `AtomicReference` gives the happens-before edge for the array's contents.
3. **When does CAS beat locking, and when does it lose?**
   It wins with low contention and many readers (no lock overhead, free snapshots). It loses with
   high contention and large state: every failed swap wasted a full copy, and retries pile up.
4. **Is the funds check safe even though it happens outside any lock?**
   Yes: it is made against one published state, which was the real ledger at the instant we read it.
   If we post, the swap proves nothing changed since; if we reject, we reject based on a true moment.
5. **Why did a lost-update bug still conserve the total, and what catches it?**
   Each published state is internally consistent; a lost update just drops whole transfers. Totals
   stay equal. Replaying every posting over the opening balances shows balances that the postings
   do not explain, which is why that invariant exists.
