package com.siddheshfulawade.settlementlab.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ledger with one lock per account. A transfer locks its two accounts in ascending
 * {@link AccountId} order, checks funds, then debits and credits.
 *
 * <p><b>Why it cannot deadlock (REQ-LAB-006):</b> a deadlock needs a cycle of threads, each holding
 * a lock the next one wants. Every thread takes locks in the same global order (lower id first), so
 * a thread holding lock {@code k} only ever waits for a lock above {@code k}. Following the waits
 * always climbs, so it can never loop back: no cycle, no deadlock. Opposing transfers A to B and
 * B to A both lock A first.
 */
public final class LockingLedger implements Ledger {

    /** One account. {@code balance} is guarded by {@code lock}: read or write it only while holding it. */
    private static final class Account {
        final ReentrantLock lock = new ReentrantLock();
        long balance;

        Account(long balance) {
            this.balance = balance;
        }
    }

    /** Fixed at construction, never changed, so it is safe to read without a lock. Sorted by id. */
    private final Map<AccountId, Account> accounts;

    private final AtomicLong nextSequence = new AtomicLong();
    private final Queue<Posting> postings = new ConcurrentLinkedQueue<>();

    /** See {@link OpeningBalances#checked} for the rules on opening balances. */
    public LockingLedger(Map<AccountId, Money> openingBalances) {
        Map<AccountId, Account> sorted = new TreeMap<>();
        OpeningBalances.checked(openingBalances).forEach((id, balance) -> sorted.put(id, new Account(balance)));
        this.accounts = Collections.unmodifiableMap(sorted);
    }

    @Override
    public ApplyResult applyWithResult(Transfer transfer) {
        if (TransferValidator.validate(transfer, accounts::containsKey).isPresent()) {
            return ApplyResult.rejected(Outcome.REJECTED_INVALID);
        }
        Account source = accounts.get(transfer.source());
        Account target = accounts.get(transfer.target());
        long amount = transfer.amount().minorUnits();

        // Lock order: lower AccountId first, always. This one rule is what prevents deadlock.
        boolean sourceFirst = transfer.source().compareTo(transfer.target()) < 0;
        Account first = sourceFirst ? source : target;
        Account second = sourceFirst ? target : source;

        first.lock.lock();
        try {
            second.lock.lock();
            try {
                // Both locks held: no other thread can see or change these two balances, so the
                // check and the two writes below are one atomic step (REQ-LAB-002).
                if (source.balance < amount) {
                    // Nothing written yet, so nothing to undo (REQ-LAB-003).
                    return ApplyResult.rejected(Outcome.REJECTED_FUNDS);
                }
                source.balance -= amount;
                target.balance += amount;
                Posting posting = new Posting(
                        nextSequence.incrementAndGet(),
                        transfer.id(),
                        transfer.source(),
                        transfer.target(),
                        transfer.amount());
                postings.add(posting);
                return ApplyResult.posted(posting);
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    @Override
    public boolean hasAccount(AccountId id) {
        return accounts.containsKey(id);
    }

    /**
     * Locks every account, in the same ascending order as {@link #apply}, copies, then unlocks. While
     * all locks are held no transfer is in flight, so the copy is consistent. Cost: it pauses all
     * transfers for the length of one copy.
     */
    @Override
    public Map<AccountId, Money> snapshot() {
        List<Account> locked = new ArrayList<>(accounts.size());
        try {
            for (Account account : accounts.values()) {
                account.lock.lock();
                locked.add(account);
            }
            Map<AccountId, Money> copy = new LinkedHashMap<>();
            accounts.forEach((id, account) -> copy.put(id, Money.of(account.balance)));
            return Collections.unmodifiableMap(copy);
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).lock.unlock();
            }
        }
    }

    /**
     * Two transfers on different accounts run in parallel, so they may add their postings out of
     * sequence order; sorting here gives callers one stable order.
     */
    @Override
    public List<Posting> postings() {
        List<Posting> copy = new ArrayList<>(postings);
        copy.sort(Comparator.comparingLong(Posting::sequence));
        return List.copyOf(copy);
    }
}
