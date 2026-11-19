package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    @DisplayName("REQ-LAB-006 account ids have one total order, by number")
    void accountIdsAreOrderedByNumber() {
        assertThat(AccountId.of(2)).isGreaterThan(AccountId.of(1));
        assertThat(AccountId.of(10)).isGreaterThan(AccountId.of(9));
        assertThat(AccountId.of(7).compareTo(AccountId.of(7))).isZero();
        assertThat(AccountId.of(42)).hasToString("acc-42");
    }

    @Test
    @DisplayName("transfer ids must not be blank")
    void transferIdMustNotBeBlank() {
        assertThatThrownBy(() -> TransferId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransferId.of(null)).isInstanceOf(NullPointerException.class);
        assertThat(TransferId.of("t-1")).hasToString("t-1");
    }

    @Test
    @DisplayName("a transfer needs every field; validity is checked separately with a reason")
    void transferNeedsEveryField() {
        var id = TransferId.of("t-1");
        assertThatThrownBy(() -> new Transfer(null, A, B, Money.of(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transfer(id, null, B, Money.of(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transfer(id, A, null, Money.of(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transfer(id, A, B, null)).isInstanceOf(NullPointerException.class);
        // An invalid amount is still a Transfer, so the engine can reject it with a reason.
        assertThat(new Transfer(id, A, A, Money.of(-1)).amount()).isEqualTo(Money.of(-1));
    }
}
