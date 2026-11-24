package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.InvariantResult;
import java.util.List;

/**
 * The final answer of a run (api-contract section 2): its settings, the numbers, and each invariant as
 * passed or failed with its first counterexample (REQ-RUN-010). Money is plain minor units.
 */
record RunReport(
        String runId,
        RunStatus status,
        boolean stoppedEarly,
        RunRequest settings,
        long durationMs,
        long transfersCompleted,
        long openingTotal,
        long closingTotal,
        long duplicatesSent,
        long duplicatesApplied,
        long throughputPerSecond,
        long p50Micros,
        long p99Micros,
        long glRetries,
        long breakerOpens,
        List<InvariantLine> invariants,
        boolean allPassed) {

    /** A counterexample is a string of at most this many characters, or null. */
    static final int MAX_COUNTEREXAMPLE = 500;

    RunReport {
        invariants = List.copyOf(invariants);
    }

    record InvariantLine(String name, boolean passed, String counterexample) {

        static InvariantLine of(InvariantResult result) {
            String text = result.counterexample().map(InvariantLine::cap).orElse(null);
            return new InvariantLine(result.invariant().name(), result.passed(), text);
        }

        /** Cuts to the limit without splitting a surrogate pair (an emoji is two chars). */
        private static String cap(String text) {
            if (text.length() <= MAX_COUNTEREXAMPLE) {
                return text;
            }
            int end = MAX_COUNTEREXAMPLE;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--; // the pair's second half would be cut off: drop the first half too
            }
            return text.substring(0, end);
        }
    }
}
