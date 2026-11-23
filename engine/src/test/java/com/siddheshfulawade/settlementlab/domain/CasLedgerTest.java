package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.C;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CasLedger-specific behaviour. The shared property and stress suites (LedgerPropertiesContract, LedgerStressContract) prove
 * REQ-LAB-002, 003, 005 and 006 for it under concurrency.
 */
class CasLedgerTest {

    private final CasLedger ledger = new CasLedger(Map.of(A, Money.of(1_000), B, Money.of(500)));

    @Test
    @DisplayName("REQ-LAB-007 a posted transfer debits the source and credits the target")
    void postsDebitAndCredit() {
        assertThat(ledger.apply(transfer("t-1", A, B, 300))).isEqualTo(Outcome.POSTED);

        assertThat(ledger.snapshot()).containsEntry(A, Money.of(700)).containsEntry(B, Money.of(800));
        assertThat(ledger.postings()).containsExactly(new Posting(1, TransferId.of("t-1"), A, B, Money.of(300)));
    }

    @Test
    @DisplayName("REQ-LAB-007 a transfer that would overdraw the source changes nothing")
    void rejectsOverdraft() {
        assertThat(ledger.apply(transfer("t-1", B, A, 501))).isEqualTo(Outcome.REJECTED_FUNDS);

        assertThat(ledger.snapshot()).containsEntry(A, Money.of(1_000)).containsEntry(B, Money.of(500));
        assertThat(ledger.postings()).isEmpty();
    }

    @Test
    @DisplayName("REQ-LAB-004 an invalid transfer is rejected and changes nothing")
    void rejectsInvalidTransfer() {
        assertThat(ledger.apply(transfer("t-1", A, C, 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("t-2", A, A, 1))).isEqualTo(Outcome.REJECTED_INVALID);

        assertThat(ledger.snapshot()).containsEntry(A, Money.of(1_000)).containsEntry(B, Money.of(500));
    }

    @Test
    @DisplayName("REQ-LAB-001 REQ-LAB-003 opening balances are checked like the locking ledger")
    void checksOpeningBalances() {
        assertThatThrownBy(() -> new CasLedger(Map.of(A, Money.of(-1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CasLedger(Map.of(A, Money.of(Long.MAX_VALUE), B, Money.of(1))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("REQ-LAB-005 a snapshot is an ordered copy that later transfers do not change")
    void snapshotIsAnOrderedCopy() {
        var opened = new CasLedger(Map.of(C, Money.of(1), A, Money.of(2), B, Money.of(3)));
        Map<AccountId, Money> before = opened.snapshot();

        opened.apply(transfer("t-1", B, A, 3));

        assertThat(before.keySet()).containsExactly(A, B, C);
        assertThat(before).containsEntry(B, Money.of(3));
        assertThat(opened.hasAccount(C)).isTrue();
        assertThat(opened.hasAccount(AccountId.of(99))).isFalse();
    }

    @Test
    @DisplayName("REQ-LAB-007 with one thread no compare-and-set ever conflicts")
    void noConflictsWithoutContention() {
        for (int i = 0; i < 100; i++) {
            ledger.apply(transfer("t-" + i, i % 2 == 0 ? A : B, i % 2 == 0 ? B : A, 1));
        }

        assertThat(ledger.conflicts()).isZero();
        assertThat(ledger.postings()).hasSize(100);
    }

    // TODO-ME: write `usesNoLocks` (REQ-LAB-007 says "without locks"): with ArchUnit's
    //  ClassFileImporter, import CasLedger.class and assert it does not depend on any class in
    //  java.util.concurrent.locks. Hint: noClasses().that().haveSimpleName("CasLedger")
    //  .should().dependOnClassesThat().resideInAPackage("java.util.concurrent.locks..").
    //  Check it: the test passes; then add a `ReentrantLock` field to CasLedger, run
    //  `./mvnw test -Dtest=CasLedgerTest` and see it fail. Remove the field.
}
