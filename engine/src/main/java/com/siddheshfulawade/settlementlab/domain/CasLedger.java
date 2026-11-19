package com.siddheshfulawade.settlementlab.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * A lock-free ledger (REQ-LAB-007). All balances live in one immutable {@link State} held in an
 * {@link AtomicReference}. A transfer reads the current state, builds the next one with the debit
 * and the credit, and swaps it in with {@code compareAndSet}. If another transfer swapped first, the
 * swap fails, nothing is published, and it retries from the new state.
 *
 * <p><b>Why it is atomic across two accounts:</b> both balance changes are in one new object, and
 * readers only ever see a whole published state, so a debit without its credit is never visible.
 *
 * <p><b>The cost:</b> every write copies the whole balance array, and under high contention many
 * swaps fail and retry ({@link #conflicts()}). The runner measures that against {@link LockingLedger}.
 */
public final class CasLedger implements Ledger {

    /**
     * One published version of the ledger. {@code balances} is never written after the state is
     * built: that immutability is what makes the lock-free reads safe. {@code sequence} is the
     * number of postings so far, so each posting's sequence is assigned in the same atomic swap.
     */
    private record State(long[] balances, long sequence) {}

    /** Account ids in ascending order; position i holds the balance at {@code balances[i]}. */
    private final List<AccountId> ids;

    private final Map<AccountId, Integer> indexOf;
    private final AtomicReference<State> state;
    private final Queue<Posting> postings = new ConcurrentLinkedQueue<>();
    private final LongAdder conflicts = new LongAdder();

    /** See {@link OpeningBalances#checked} for the rules on opening balances. */
    public CasLedger(Map<AccountId, Money> openingBalances) {
        Map<AccountId, Long> sorted = OpeningBalances.checked(openingBalances);
        this.ids = List.copyOf(sorted.keySet());
        Map<AccountId, Integer> index = new HashMap<>();
        long[] balances = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            index.put(ids.get(i), i);
            balances[i] = sorted.get(ids.get(i));
        }
        this.indexOf = Map.copyOf(index);
        this.state = new AtomicReference<>(new State(balances, 0));
    }

    @Override
    public ApplyResult applyWithResult(Transfer transfer) {
        if (TransferValidator.validate(transfer, indexOf::containsKey).isPresent()) {
            return ApplyResult.rejected(Outcome.REJECTED_INVALID);
        }
        int source = indexOf.get(transfer.source());
        int target = indexOf.get(transfer.target());
        long amount = transfer.amount().minorUnits();

        while (true) {
            State current = state.get();
            if (current.balances()[source] < amount) {
                // Decided on one whole published state, so it was true at that moment (REQ-LAB-003).
                return ApplyResult.rejected(Outcome.REJECTED_FUNDS);
            }
            long[] next = current.balances().clone();
            next[source] -= amount;
            next[target] += amount;
            State updated = new State(next, current.sequence() + 1);

            // Succeeds only if nobody published since we read `current`; otherwise retry on fresh data.
            if (state.compareAndSet(current, updated)) {
                Posting posting = new Posting(
                        updated.sequence(), transfer.id(), transfer.source(), transfer.target(), transfer.amount());
                postings.add(posting);
                return ApplyResult.posted(posting);
            }
            conflicts.increment();
        }
    }

    @Override
    public boolean hasAccount(AccountId id) {
        return indexOf.containsKey(id);
    }

    /** One read of the current state: no lock, and never a half-applied transfer. */
    @Override
    public Optional<Money> balance(AccountId id) {
        Integer index = indexOf.get(id);
        return index == null
                ? Optional.empty()
                : Optional.of(Money.of(state.get().balances()[index]));
    }

    /** One read of the reference gives a whole published state: consistent with no lock at all. */
    @Override
    public Map<AccountId, Money> snapshot() {
        long[] balances = state.get().balances();
        Map<AccountId, Money> copy = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            copy.put(ids.get(i), Money.of(balances[i]));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * A posting is added just after its swap succeeds, so a call made while transfers are running
     * may briefly miss the newest ones. Once every {@link #apply} has returned, it is complete.
     */
    @Override
    public List<Posting> postings() {
        List<Posting> copy = new ArrayList<>(postings);
        copy.sort(Comparator.comparingLong(Posting::sequence));
        return List.copyOf(copy);
    }

    /** How many compare-and-set attempts failed and had to retry: a measure of contention. */
    public long conflicts() {
        return conflicts.sum();
    }
}
