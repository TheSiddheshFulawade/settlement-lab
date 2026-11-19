package com.siddheshfulawade.settlementlab.domain;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** One result per invariant, in {@link Invariant} order. */
public record InvariantReport(List<InvariantResult> results) {

    public InvariantReport {
        results = List.copyOf(results);
    }

    public boolean allPassed() {
        return results.stream().allMatch(InvariantResult::passed);
    }

    public Optional<InvariantResult> firstFailure() {
        return results.stream().filter(r -> !r.passed()).findFirst();
    }

    /** One line per invariant, for test messages and the runner. */
    @Override
    public String toString() {
        return results.stream().map(InvariantResult::toString).collect(Collectors.joining(System.lineSeparator()));
    }
}
