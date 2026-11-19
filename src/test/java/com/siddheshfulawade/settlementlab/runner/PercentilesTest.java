package com.siddheshfulawade.settlementlab.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    @DisplayName("nearest-rank percentiles over the recorded latencies")
    void nearestRank() {
        long[] values = new long[100];
        for (int i = 0; i < 100; i++) {
            values[i] = 100 - i; // unsorted on purpose
        }

        assertThat(Percentiles.of(values, 50)).isEqualTo(50);
        assertThat(Percentiles.of(values, 99)).isEqualTo(99);
        assertThat(Percentiles.of(values, 100)).isEqualTo(100);
        assertThat(Percentiles.of(new long[0], 50)).isZero();
    }

    // TODO-ME: write `smallSamples`: nearest rank on tiny inputs. Check that of({7}, 99) is 7, that
    //  of({1, 2}, 50) is 1 (rank ceil(0.5 * 2) = 1, so the first value) and of({1, 2}, 51) is 2.
    //  Check it: the test passes; then change Math.ceil to Math.round in Percentiles.of and see
    //  which case fails, and explain why in one sentence.
}
