package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.C;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferValidatorTest {

    private final Predicate<AccountId> knownAccounts = Set.of(A, B)::contains;

    @Test
    @DisplayName("REQ-LAB-004 a positive amount between two known accounts is valid")
    void validTransferHasNoReason() {
        assertThat(TransferValidator.validate(transfer("t", A, B, 1), knownAccounts))
                .isEmpty();
    }

    @Test
    @DisplayName("REQ-LAB-004 rejects an amount of zero or less")
    void rejectsNonPositiveAmount() {
        assertThat(TransferValidator.validate(transfer("t", A, B, 0), knownAccounts))
                .contains(InvalidReason.NON_POSITIVE_AMOUNT);
        assertThat(TransferValidator.validate(transfer("t", A, B, -1), knownAccounts))
                .contains(InvalidReason.NON_POSITIVE_AMOUNT);
    }

    @Test
    @DisplayName("REQ-LAB-004 rejects the same source and target")
    void rejectsSameAccount() {
        assertThat(TransferValidator.validate(transfer("t", A, A, 1), knownAccounts))
                .contains(InvalidReason.SAME_ACCOUNT);
    }

    @Test
    @DisplayName("REQ-LAB-004 rejects an unknown source or target")
    void rejectsUnknownAccounts() {
        assertThat(TransferValidator.validate(transfer("t", C, B, 1), knownAccounts))
                .contains(InvalidReason.UNKNOWN_SOURCE);
        assertThat(TransferValidator.validate(transfer("t", A, C, 1), knownAccounts))
                .contains(InvalidReason.UNKNOWN_TARGET);
    }
}
