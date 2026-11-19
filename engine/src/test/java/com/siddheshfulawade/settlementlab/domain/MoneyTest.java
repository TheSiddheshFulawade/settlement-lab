package com.siddheshfulawade.settlementlab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("REQ-LAB-001 adds and subtracts exact minor units")
    void addsAndSubtractsExactly() {
        assertThat(Money.of(1_000).plus(Money.of(1))).isEqualTo(Money.of(1_001));
        assertThat(Money.of(1_000).minus(Money.of(1))).isEqualTo(Money.of(999));
    }

    @Test
    @DisplayName("REQ-LAB-001 overflow throws instead of wrapping around")
    void overflowThrows() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE).minus(Money.of(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("REQ-LAB-004 only amounts above zero are positive")
    void positiveMeansAboveZero() {
        assertThat(Money.of(1).isPositive()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
        assertThat(Money.of(-1).isPositive()).isFalse();
    }

    @Test
    @DisplayName("prints minor units in international format")
    void printsInInternationalFormat() {
        assertThat(Money.of(1_234_567)).hasToString("1,234,567");
        assertThat(Money.of(-5)).hasToString("-5");
    }
}
