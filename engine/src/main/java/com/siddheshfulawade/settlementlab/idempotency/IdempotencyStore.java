package com.siddheshfulawade.settlementlab.idempotency;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Makes sure each transfer id is processed at most once, and that every caller with that id gets the
 * same outcome, even when duplicates arrive at the same moment (REQ-LAB-008).
 *
 * <p>How: one {@link CompletableFuture} per id in a {@link ConcurrentHashMap}. {@code putIfAbsent} is
 * atomic, so of any number of racing callers exactly one installs its future and becomes the
 * <em>owner</em>; it does the work and completes the future. Everyone else gets that same future
 * (as a read-only copy) and sees the owner's outcome when it is ready.
 *
 * <p>No expiry in v1: memory grows with the number of distinct ids in a run (SPEC.md section 4.2).
 */
public final class IdempotencyStore {

    /**
     * The result of {@link #claim}. Only the owner may complete {@code outcome}. A duplicate's
     * {@code outcome} is a copy: completing it does not change what anyone else sees.
     */
    public record Claim(CompletableFuture<Outcome> outcome, boolean owner) {}

    private final ConcurrentHashMap<TransferId, CompletableFuture<Outcome>> outcomes = new ConcurrentHashMap<>();
    private final LongAdder duplicates = new LongAdder();

    /** Claims an id. Exactly one caller per id gets {@code owner == true}, until it is released. */
    public Claim claim(TransferId id) {
        CompletableFuture<Outcome> fresh = new CompletableFuture<>();
        CompletableFuture<Outcome> existing = outcomes.putIfAbsent(id, fresh);
        if (existing == null) {
            return new Claim(fresh, true);
        }
        duplicates.increment(); // reported as DUPLICATE in metrics; the caller still gets the real outcome
        return new Claim(existing.copy(), false);
    }

    /**
     * Forgets an owned claim, so the same id can be submitted again. For transfers that were never
     * processed (for example {@code QUEUE_FULL}): a retry must not be answered with "queue full"
     * forever. Does nothing unless {@code claim} is the current owner of {@code id}.
     *
     * @return true if the claim was removed
     */
    public boolean release(TransferId id, Claim claim) {
        return claim.owner() && outcomes.remove(id, claim.outcome());
    }

    /**
     * Claims the id and, if this caller is the owner, runs {@code work} on this thread and completes
     * the outcome with its result. Duplicates do not run {@code work}.
     *
     * <p>If {@code work} throws, nothing was applied, so the claim is released (a retry may run) and the
     * returned future completes exceptionally.
     */
    public CompletableFuture<Outcome> execute(TransferId id, Supplier<Outcome> work) {
        Claim claim = claim(id);
        if (!claim.owner()) {
            return claim.outcome();
        }
        try {
            claim.outcome().complete(work.get());
        } catch (RuntimeException e) {
            outcomes.remove(id, claim.outcome());
            claim.outcome().completeExceptionally(e);
        }
        return claim.outcome();
    }

    /** How many calls found their id already claimed. */
    public long duplicates() {
        return duplicates.sum();
    }

    /** How many ids are currently remembered. */
    public int size() {
        return outcomes.size();
    }
}
