package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplyResultTest {

    private static final Posting POSTING = new Posting(1, TransferId.of("t-1"), A, B, Money.of(5));

    @Test
    @DisplayName("a posting is present exactly when the outcome is POSTED")
    void postingPresentExactlyWhenPosted() {
        assertThat(ApplyResult.posted(POSTING).posting()).contains(POSTING);
        assertThat(ApplyResult.rejected(Outcome.REJECTED_FUNDS).posting()).isEmpty();

        assertThatThrownBy(() -> new ApplyResult(Outcome.POSTED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyResult(Outcome.REJECTED_FUNDS, Optional.of(POSTING)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplyResult.rejected(Outcome.QUEUE_FULL)).isInstanceOf(IllegalArgumentException.class);
    }
}
