package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.Invariant;
import com.siddheshfulawade.settlementlab.domain.InvariantResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-RUN-010: a counterexample is a string of at most 500 characters, or null.
 * ASSUMED API: {@code static RunReport.InvariantLine RunReport.InvariantLine.of(InvariantResult)}.
 */
class RunReportTest {

    @Test
    @DisplayName("REQ-RUN-010 a passed invariant has a null counterexample")
    void passedHasNull() {
        RunReport.InvariantLine line = RunReport.InvariantLine.of(InvariantResult.pass(Invariant.TOTAL_CONSERVED));

        assertThat(line.name()).isEqualTo("TOTAL_CONSERVED");
        assertThat(line.passed()).isTrue();
        assertThat(line.counterexample()).isNull();
    }

    @Test
    @DisplayName("REQ-RUN-010 a short counterexample is kept whole")
    void shortCounterexampleKept() {
        RunReport.InvariantLine line =
                RunReport.InvariantLine.of(InvariantResult.fail(Invariant.NO_NEGATIVE_BALANCE, "account 7 has -3"));

        assertThat(line.passed()).isFalse();
        assertThat(line.counterexample()).isEqualTo("account 7 has -3");
    }

    @Test
    @DisplayName("REQ-RUN-010 a counterexample of exactly 500 characters is kept; longer ones are cut to 500 or fewer")
    void longCounterexampleIsCapped() {
        String exact = "x".repeat(500);
        String huge = "y".repeat(5_000);

        assertThat(RunReport.InvariantLine.of(InvariantResult.fail(Invariant.POSTED_AT_MOST_ONCE, exact))
                        .counterexample())
                .isEqualTo(exact);
        String cut = RunReport.InvariantLine.of(InvariantResult.fail(Invariant.POSTED_AT_MOST_ONCE, huge))
                .counterexample();
        assertThat(cut).hasSizeLessThanOrEqualTo(500).startsWith("yyyy");
        assertThat(RunReport.InvariantLine.of(InvariantResult.fail(Invariant.POSTED_AT_MOST_ONCE, "z".repeat(501)))
                        .counterexample())
                .hasSizeLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("REQ-RUN-010 cutting never splits a surrogate pair")
    void cutKeepsValidUnicode() {
        String text = "a" + "😀".repeat(400); // 1 + 800 UTF-16 units

        String cut = RunReport.InvariantLine.of(InvariantResult.fail(Invariant.GL_MATCHES_POSTINGS, text))
                .counterexample();

        assertThat(cut.length()).isLessThanOrEqualTo(500);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }
}
