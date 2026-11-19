package com.siddheshfulawade.settlementlab.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks a finished run (or a snapshot during one) against every {@link Invariant} and reports the
 * first counterexample for each (REQ-LAB-014). Pure functions over plain data: it knows nothing
 * about how the ledger works, so it can judge any ledger.
 *
 * <p>Accounts are visited in id order and postings in sequence order, so the "first"
 * counterexample is the same on every run.
 */
public final class InvariantChecker {

    private InvariantChecker() {}

    /** Runs every ledger invariant, in {@link Invariant} order. */
    public static InvariantReport checkLedger(
            Map<AccountId, Money> opening,
            Map<AccountId, Money> balances,
            List<Posting> postings,
            Collection<TransferId> accepted) {
        return new InvariantReport(List.of(
                totalConserved(opening, balances),
                noNegativeBalance(balances),
                postedAtMostOnce(postings),
                postingsExplainBalances(opening, balances, postings),
                postingsMatchAccepted(postings, accepted)));
    }

    public static InvariantResult totalConserved(Map<AccountId, Money> opening, Map<AccountId, Money> balances) {
        long openingTotal = total(opening);
        long snapshotTotal = total(balances);
        if (openingTotal == snapshotTotal) {
            return InvariantResult.pass(Invariant.TOTAL_CONSERVED);
        }
        return InvariantResult.fail(
                Invariant.TOTAL_CONSERVED,
                String.format(Locale.ROOT, "opening total %,d but snapshot total %,d", openingTotal, snapshotTotal));
    }

    public static InvariantResult noNegativeBalance(Map<AccountId, Money> balances) {
        for (Map.Entry<AccountId, Money> entry : new TreeMap<>(balances).entrySet()) {
            if (entry.getValue().minorUnits() < 0) {
                return InvariantResult.fail(
                        Invariant.NO_NEGATIVE_BALANCE, entry.getKey() + " has balance " + entry.getValue());
            }
        }
        return InvariantResult.pass(Invariant.NO_NEGATIVE_BALANCE);
    }

    public static InvariantResult postedAtMostOnce(List<Posting> postings) {
        Map<TransferId, Long> firstSequence = new HashMap<>();
        for (Posting posting : bySequence(postings)) {
            Long earlier = firstSequence.putIfAbsent(posting.transferId(), posting.sequence());
            if (earlier != null) {
                return InvariantResult.fail(
                        Invariant.POSTED_AT_MOST_ONCE,
                        "transfer " + posting.transferId() + " posted at sequences " + earlier + " and "
                                + posting.sequence());
            }
        }
        return InvariantResult.pass(Invariant.POSTED_AT_MOST_ONCE);
    }

    public static InvariantResult postingsExplainBalances(
            Map<AccountId, Money> opening, Map<AccountId, Money> balances, List<Posting> postings) {
        Map<AccountId, Long> replayed = new TreeMap<>();
        opening.forEach((id, money) -> replayed.put(id, money.minorUnits()));
        balances.keySet().forEach(id -> replayed.putIfAbsent(id, 0L));
        for (Posting posting : postings) {
            long amount = posting.amount().minorUnits();
            replayed.merge(posting.source(), -amount, Math::addExact);
            replayed.merge(posting.target(), amount, Math::addExact);
        }
        for (Map.Entry<AccountId, Long> entry : replayed.entrySet()) {
            long actual = balances.getOrDefault(entry.getKey(), Money.ZERO).minorUnits();
            if (actual != entry.getValue()) {
                return InvariantResult.fail(
                        Invariant.POSTINGS_EXPLAIN_BALANCES,
                        String.format(
                                Locale.ROOT,
                                "%s: replaying postings gives %,d but the ledger has %,d",
                                entry.getKey(),
                                entry.getValue(),
                                actual));
            }
        }
        return InvariantResult.pass(Invariant.POSTINGS_EXPLAIN_BALANCES);
    }

    public static InvariantResult postingsMatchAccepted(List<Posting> postings, Collection<TransferId> accepted) {
        Set<String> posted = new TreeSet<>();
        postings.forEach(p -> posted.add(p.transferId().value()));
        Set<String> acceptedIds = new TreeSet<>();
        accepted.forEach(id -> acceptedIds.add(id.value()));

        for (String id : acceptedIds) {
            if (!posted.contains(id)) {
                return InvariantResult.fail(Invariant.POSTINGS_MATCH_ACCEPTED, id + " was accepted but has no posting");
            }
        }
        for (String id : posted) {
            if (!acceptedIds.contains(id)) {
                return InvariantResult.fail(Invariant.POSTINGS_MATCH_ACCEPTED, id + " was posted but never accepted");
            }
        }
        return InvariantResult.pass(Invariant.POSTINGS_MATCH_ACCEPTED);
    }

    private static long total(Map<AccountId, Money> balances) {
        long total = 0;
        for (Money money : balances.values()) {
            total = Math.addExact(total, money.minorUnits());
        }
        return total;
    }

    private static List<Posting> bySequence(List<Posting> postings) {
        return postings.stream()
                .sorted((a, b) -> Long.compare(a.sequence(), b.sequence()))
                .toList();
    }
}
