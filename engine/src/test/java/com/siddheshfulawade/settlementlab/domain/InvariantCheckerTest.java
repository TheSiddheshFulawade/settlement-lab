package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvariantCheckerTest {

    private static final Map<AccountId, Money> OPENING = Map.of(A, Money.of(1_000), B, Money.of(500));

    private static Posting posting(long sequence, String id, AccountId from, AccountId to, long amount) {
        return new Posting(sequence, TransferId.of(id), from, to, Money.of(amount));
    }

    @Test
    @DisplayName("REQ-LAB-014 a consistent ledger passes every invariant")
    void consistentLedgerPasses() {
        var postings = List.of(posting(1, "t-1", A, B, 100), posting(2, "t-2", B, A, 50));
        var balances = Map.of(A, Money.of(950), B, Money.of(550));

        InvariantReport report = InvariantChecker.checkLedger(
                OPENING, balances, postings, Set.of(TransferId.of("t-1"), TransferId.of("t-2")));

        assertThat(report.allPassed()).isTrue();
        assertThat(report.results())
                .extracting(InvariantResult::invariant)
                .containsExactly(
                        Invariant.TOTAL_CONSERVED,
                        Invariant.NO_NEGATIVE_BALANCE,
                        Invariant.POSTED_AT_MOST_ONCE,
                        Invariant.POSTINGS_EXPLAIN_BALANCES,
                        Invariant.POSTINGS_MATCH_ACCEPTED);
        assertThat(report.firstFailure()).isEmpty();
        assertThat(report.toString()).contains("PASS TOTAL_CONSERVED");
    }

    @Test
    @DisplayName("REQ-LAB-014 reports created money with the totals")
    void reportsCreatedMoney() {
        InvariantResult result = InvariantChecker.totalConserved(OPENING, Map.of(A, Money.of(1_001), B, Money.of(500)));

        assertThat(result.passed()).isFalse();
        assertThat(result.counterexample()).contains("opening total 1,500 but snapshot total 1,501");
    }

    @Test
    @DisplayName("REQ-LAB-014 reports the first negative balance")
    void reportsNegativeBalance() {
        InvariantResult result = InvariantChecker.noNegativeBalance(Map.of(B, Money.of(-2), A, Money.of(-1)));

        assertThat(result.counterexample()).contains("acc-1 has balance -1");
    }

    @Test
    @DisplayName("REQ-LAB-014 reports a transfer posted twice")
    void reportsDuplicatePosting() {
        var postings = List.of(posting(1, "t-1", A, B, 1), posting(2, "t-2", A, B, 1), posting(3, "t-1", A, B, 1));

        InvariantResult result = InvariantChecker.postedAtMostOnce(postings);

        assertThat(result.counterexample()).contains("transfer t-1 posted at sequences 1 and 3");
    }

    @Test
    @DisplayName("REQ-LAB-014 reports a balance the postings do not explain")
    void reportsUnexplainedBalance() {
        var postings = List.of(posting(1, "t-1", A, B, 100));
        var balances = Map.of(A, Money.of(900), B, Money.of(599));

        InvariantResult result = InvariantChecker.postingsExplainBalances(OPENING, balances, postings);

        assertThat(result.counterexample()).contains("acc-2: replaying postings gives 600 but the ledger has 599");
    }

    @Test
    @DisplayName("REQ-LAB-014 reports an accepted transfer with no posting, and a posting nobody accepted")
    void reportsMismatchWithAccepted() {
        var postings = List.of(posting(1, "t-1", A, B, 1));

        assertThat(InvariantChecker.postingsMatchAccepted(postings, Set.of(TransferId.of("t-1"), TransferId.of("t-2")))
                        .counterexample())
                .contains("t-2 was accepted but has no posting");
        assertThat(InvariantChecker.postingsMatchAccepted(postings, Set.of()).counterexample())
                .contains("t-1 was posted but never accepted");
    }

    @Test
    @DisplayName("REQ-LAB-014 the report names the first failure and its counterexample")
    void reportNamesFirstFailure() {
        var balances = Map.of(A, Money.of(-1), B, Money.of(500));

        InvariantReport report = InvariantChecker.checkLedger(OPENING, balances, List.of(), Set.of());

        assertThat(report.allPassed()).isFalse();
        assertThat(report.firstFailure())
                .get()
                .extracting(InvariantResult::invariant)
                .isEqualTo(Invariant.TOTAL_CONSERVED);
        assertThat(report.toString()).contains("FAIL NO_NEGATIVE_BALANCE: acc-1 has balance -1");
    }

    @Test
    @DisplayName("REQ-LAB-012 REQ-LAB-014 the general ledger must hold exactly the ledger's postings")
    void generalLedgerMatchesPostings() {
        var p1 = posting(1, "t-1", A, B, 100);
        var p2 = posting(2, "t-2", B, A, 50);

        assertThat(InvariantChecker.glMatchesPostings(List.of(p1, p2), List.of(p2, p1))
                        .passed())
                .isTrue();
        assertThat(InvariantChecker.glMatchesPostings(List.of(p1, p2), List.of(p1))
                        .counterexample())
                .contains("posting t-2 is missing from the general ledger");
        assertThat(InvariantChecker.glMatchesPostings(List.of(p1), List.of(p1, p2))
                        .counterexample())
                .contains("general ledger entry t-2 has no ledger posting");
        assertThat(InvariantChecker.glMatchesPostings(List.of(p1), List.of(posting(1, "t-1", A, B, 99)))
                        .counterexample())
                .contains("general ledger entry t-1 differs from the ledger posting");
    }

    @Test
    @DisplayName("REQ-LAB-014 a run report has every invariant, the general ledger one last")
    void runReportHasEveryInvariant() {
        var postings = List.of(posting(1, "t-1", A, B, 100));
        var balances = Map.of(A, Money.of(900), B, Money.of(600));

        InvariantReport report =
                InvariantChecker.checkRun(OPENING, balances, postings, Set.of(TransferId.of("t-1")), postings);

        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).extracting(InvariantResult::invariant).containsExactly(Invariant.values());
    }
}
