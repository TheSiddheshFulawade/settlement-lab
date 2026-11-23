package com.siddheshfulawade.settlementlab.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties every {@link Ledger} must have, for random accounts and random transfers (valid and
 * invalid). Extended by one concrete test class per ledger (engine: LockingLedger, CasLedger; later
 * the PostgreSQL ledgers from the ledger-postgres module via the engine test-jar), so every ledger
 * gets exactly the same proofs. A subclass only says how to build its ledger.
 */
public abstract class LedgerPropertiesContract {

    /** A fresh ledger holding exactly these opening balances. */
    protected abstract Ledger newLedger(Map<AccountId, Money> openingBalances);

    /** Random opening balances and a random list of transfers between them. */
    public record Scenario(Map<AccountId, Money> opening, List<Transfer> transfers) {}

    private record Move(long source, long target, long amount) {}

    @Provide
    public Arbitrary<Scenario> scenarios() {
        return Arbitraries.integers().between(2, 6).flatMap(accounts -> {
            Arbitrary<List<Long>> balances =
                    Arbitraries.longs().between(0, 10_000).list().ofSize(accounts);
            // Ids 1..accounts exist; accounts + 1 is unknown. Amounts include zero and negatives.
            Arbitrary<Move> move = Combinators.combine(
                            Arbitraries.longs().between(1, accounts + 1),
                            Arbitraries.longs().between(1, accounts + 1),
                            Arbitraries.longs().between(-5, 12_000))
                    .as(Move::new);
            return Combinators.combine(balances, move.list().ofMaxSize(40)).as(LedgerPropertiesContract::scenario);
        });
    }

    private static Scenario scenario(List<Long> balances, List<Move> moves) {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (int i = 0; i < balances.size(); i++) {
            opening.put(AccountId.of(i + 1), Money.of(balances.get(i)));
        }
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            transfers.add(Fixtures.transfer("t-" + i, AccountId.of(m.source()), AccountId.of(m.target()), m.amount()));
        }
        return new Scenario(opening, transfers);
    }

    @Property
    @Label("REQ-LAB-005 total is conserved and every invariant holds after any sequence of transfers")
    public void everyInvariantHolds(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());
        Set<TransferId> accepted = new HashSet<>();

        for (Transfer t : scenario.transfers()) {
            if (ledger.apply(t) == Outcome.POSTED) {
                accepted.add(t.id());
            }
        }

        InvariantReport report =
                InvariantChecker.checkLedger(scenario.opening(), ledger.snapshot(), ledger.postings(), accepted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
    }

    @Property
    @Label("REQ-LAB-003 no balance is ever negative, after every single transfer")
    public void noBalanceGoesNegative(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());

        for (Transfer t : scenario.transfers()) {
            ledger.apply(t);
            assertThat(ledger.snapshot().values()).allMatch(balance -> balance.minorUnits() >= 0);
        }
    }

    @Property
    @Label("REQ-LAB-002 REQ-LAB-003 a rejected transfer leaves balances and postings unchanged")
    public void rejectionChangesNothing(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());

        for (Transfer t : scenario.transfers()) {
            Map<AccountId, Money> before = ledger.snapshot();
            int postingsBefore = ledger.postings().size();

            if (ledger.apply(t) != Outcome.POSTED) {
                assertThat(ledger.snapshot()).isEqualTo(before);
                assertThat(ledger.postings()).hasSize(postingsBefore);
            }
        }
    }

    @Property
    @Label("REQ-LAB-002 REQ-LAB-003 REQ-LAB-004 outcomes and balances match a simple sequential model")
    public void matchesSequentialModel(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());
        Map<AccountId, Long> model = new HashMap<>();
        scenario.opening().forEach((id, money) -> model.put(id, money.minorUnits()));

        for (Transfer t : scenario.transfers()) {
            Outcome expected = modelApply(model, t);
            assertThat(ledger.apply(t)).as(t.toString()).isEqualTo(expected);
        }

        Map<AccountId, Money> expectedBalances = new HashMap<>();
        model.forEach((id, units) -> expectedBalances.put(id, Money.of(units)));
        assertThat(ledger.snapshot()).isEqualTo(expectedBalances);
    }

    @Property
    @Label("REQ-LAB-011 a POSTED result carries the posting the ledger recorded; a rejection carries none")
    public void resultCarriesThePosting(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());

        for (Transfer t : scenario.transfers()) {
            ApplyResult result = ledger.applyWithResult(t);
            if (result.outcome() == Outcome.POSTED) {
                List<Posting> postings = ledger.postings();
                assertThat(result.posting()).contains(postings.get(postings.size() - 1));
                assertThat(result.posting().get().transferId()).isEqualTo(t.id());
            } else {
                assertThat(result.posting()).isEmpty();
            }
        }
    }

    @Property
    @Label("REQ-API-004 balance(id) agrees with the snapshot for every account, and is empty for an unknown one")
    public void balanceAgreesWithSnapshot(@ForAll("scenarios") Scenario scenario) {
        Ledger ledger = newLedger(scenario.opening());
        scenario.transfers().forEach(ledger::apply);

        ledger.snapshot().forEach((id, money) -> assertThat(ledger.balance(id)).contains(money));
        assertThat(ledger.balance(AccountId.of(10_000))).isEmpty();
    }

    /** The obvious single-threaded ledger: the reference the real ones must agree with. */
    private static Outcome modelApply(Map<AccountId, Long> model, Transfer t) {
        long amount = t.amount().minorUnits();
        if (amount <= 0
                || t.source().equals(t.target())
                || !model.containsKey(t.source())
                || !model.containsKey(t.target())) {
            return Outcome.REJECTED_INVALID;
        }
        if (model.get(t.source()) < amount) {
            return Outcome.REJECTED_FUNDS;
        }
        model.merge(t.source(), -amount, Long::sum);
        model.merge(t.target(), amount, Long::sum);
        return Outcome.POSTED;
    }

    @Property(tries = 1)
    @Label("TODO-ME whole-balance transfer leaves the source at exactly 0 and is POSTED")
    public void wholeBalanceTransferEmptiesTheSource() {
        // TODO-ME: write this property or test. Open two accounts (Fixtures.uniformAccounts(2, n)),
        // transfer the source's entire balance to the other, and assert the outcome is POSTED and the
        // source balance is exactly Money.of(0). Check it: change LockingLedger's funds test from
        // "<" to "<=" and this must fail; restore it and it passes.
    }
}
