package com.siddheshfulawade.settlementlab.domain;

import java.util.Objects;
import java.util.Optional;

/** Pass or fail for one invariant; a failure carries the first counterexample found. */
public record InvariantResult(Invariant invariant, Optional<String> counterexample) {

    public InvariantResult {
        Objects.requireNonNull(invariant, "invariant");
        Objects.requireNonNull(counterexample, "counterexample");
    }

    public static InvariantResult pass(Invariant invariant) {
        return new InvariantResult(invariant, Optional.empty());
    }

    public static InvariantResult fail(Invariant invariant, String counterexample) {
        return new InvariantResult(invariant, Optional.of(counterexample));
    }

    public boolean passed() {
        return counterexample.isEmpty();
    }

    @Override
    public String toString() {
        return counterexample.map(c -> "FAIL " + invariant + ": " + c).orElse("PASS " + invariant);
    }
}
