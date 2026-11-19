package com.siddheshfulawade.settlementlab.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class IdempotencyPropertiesTest {

    /** One call: an id (few distinct values, so repeats are common) and the outcome its work would return. */
    record Call(String id, Outcome outcome) {}

    @Provide
    Arbitrary<List<Call>> calls() {
        Arbitrary<String> ids = Arbitraries.integers().between(0, 9).map(i -> "t-" + i);
        Arbitrary<Outcome> outcomes = Arbitraries.of(Outcome.POSTED, Outcome.REJECTED_FUNDS, Outcome.REJECTED_INVALID);
        return Combinators.combine(ids, outcomes).as(Call::new).list().ofMaxSize(60);
    }

    @Property
    @Label("REQ-LAB-008 work runs once per distinct id, and every call gets that id's first outcome")
    void workRunsOncePerId(@ForAll("calls") List<Call> calls) {
        var store = new IdempotencyStore();
        Map<String, Integer> runs = new HashMap<>();
        Map<String, Outcome> firstOutcome = new HashMap<>();

        for (Call call : calls) {
            firstOutcome.putIfAbsent(call.id(), call.outcome());
            Outcome seen = store.execute(TransferId.of(call.id()), () -> {
                        runs.merge(call.id(), 1, Integer::sum);
                        return call.outcome();
                    })
                    .join();
            assertThat(seen).isEqualTo(firstOutcome.get(call.id()));
        }

        assertThat(runs.values()).allMatch(count -> count == 1);
        assertThat(runs.keySet()).isEqualTo(firstOutcome.keySet());
        assertThat(store.duplicates()).isEqualTo(calls.size() - firstOutcome.size());
    }
}
