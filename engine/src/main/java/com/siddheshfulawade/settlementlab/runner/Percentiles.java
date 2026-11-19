package com.siddheshfulawade.settlementlab.runner;

import java.util.Arrays;

/** Nearest-rank percentiles: the smallest value with at least p % of values at or below it. */
final class Percentiles {

    private Percentiles() {}

    static long of(long[] values, int percentile) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }
}
