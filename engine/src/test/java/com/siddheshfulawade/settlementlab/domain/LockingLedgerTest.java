package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.C;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LockingLedgerTest {

    private final LockingLedger ledger = new LockingLedger(Map.of(A, Money.of(1_000), B, Money.of(500)));

    @Test
    @DisplayName("REQ-LAB-002 a posted transfer debits the source and credits the target")
    void postsDebitAndCredit() {
        Outcome outcome = ledger.apply(transfer("t-1", A, B, 300));

        assertThat(outcome).isEqualTo(Outcome.POSTED);
        assertThat(ledger.snapshot()).containsEntry(A, Money.of(700)).containsEntry(B, Money.of(800));
        assertThat(ledger.postings()).containsExactly(new Posting(1, TransferId.of("t-1"), A, B, Money.of(300)));
    }

    @Test
    @DisplayName("REQ-LAB-003 a transfer that would overdraw the source changes nothing")
    void rejectsOverdraft() {
        Outcome outcome = ledger.apply(transfer("t-1", B, A, 501));

        assertThat(outcome).isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(ledger.snapshot()).containsEntry(A, Money.of(1_000)).containsEntry(B, Money.of(500));
        assertThat(ledger.postings()).isEmpty();
    }

    // TODO-ME: write `postsTransferOfTheWholeBalance` (REQ-LAB-003 boundary): moving exactly B's whole
    //  balance (500) to A must be POSTED and leave B at 0, A at 1,500 and one posting.
    //  Check it: the test passes; then change `<` to `<=` in the funds check in LockingLedger.apply,
    //  run `./mvnw test -Dtest=LockingLedgerTest` and see only your test fail. Undo the change.

    @Test
    @DisplayName("REQ-LAB-004 an invalid transfer is rejected and changes nothing")
    void rejectsInvalidTransfers() {
        List<Transfer> invalid = List.of(
                transfer("zero", A, B, 0),
                transfer("negative", A, B, -10),
                transfer("same", A, A, 10),
                transfer("unknown-source", C, B, 10),
                transfer("unknown-target", A, C, 10));

        for (Transfer t : invalid) {
            assertThat(ledger.apply(t)).as(t.id().value()).isEqualTo(Outcome.REJECTED_INVALID);
        }
        assertThat(ledger.snapshot()).containsEntry(A, Money.of(1_000)).containsEntry(B, Money.of(500));
        assertThat(ledger.postings()).isEmpty();
    }

    @Test
    @DisplayName("REQ-LAB-001 balances are exact up to the long limit")
    void exactAtTheLongLimit() {
        var big = new LockingLedger(Map.of(A, Money.of(Long.MAX_VALUE - 1), B, Money.of(1)));

        assertThat(big.apply(transfer("t-1", B, A, 1))).isEqualTo(Outcome.POSTED);
        assertThat(big.snapshot()).containsEntry(A, Money.of(Long.MAX_VALUE)).containsEntry(B, Money.ZERO);
    }

    @Test
    @DisplayName("REQ-LAB-001 opening balances whose total overflows a long are refused")
    void refusesOverflowingOpeningTotal() {
        assertThatThrownBy(() -> new LockingLedger(Map.of(A, Money.of(Long.MAX_VALUE), B, Money.of(1))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("REQ-LAB-003 negative opening balances are refused")
    void refusesNegativeOpeningBalance() {
        assertThatThrownBy(() -> new LockingLedger(Map.of(A, Money.of(-1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acc-1");
    }

    @Test
    @DisplayName("knows exactly the accounts it was opened with")
    void knowsItsAccounts() {
        assertThat(ledger.hasAccount(A)).isTrue();
        assertThat(ledger.hasAccount(C)).isFalse();
    }

    @Test
    @DisplayName("REQ-LAB-005 a snapshot is an ordered copy that later transfers do not change")
    void snapshotIsAnOrderedCopy() {
        var opened = new LockingLedger(Map.of(C, Money.of(1), A, Money.of(2), B, Money.of(3)));
        Map<AccountId, Money> before = opened.snapshot();

        opened.apply(transfer("t-1", B, A, 3));

        assertThat(before.keySet()).containsExactly(A, B, C);
        assertThat(before).containsEntry(B, Money.of(3));
        assertThatThrownBy(() -> before.put(A, Money.ZERO)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("REQ-LAB-002 postings come back in sequence order")
    void postingsInSequenceOrder() {
        ledger.apply(transfer("t-1", A, B, 1));
        ledger.apply(transfer("t-2", B, A, 2));
        ledger.apply(transfer("t-3", A, B, 3));

        assertThat(ledger.postings()).extracting(Posting::sequence).containsExactly(1L, 2L, 3L);
    }
}
