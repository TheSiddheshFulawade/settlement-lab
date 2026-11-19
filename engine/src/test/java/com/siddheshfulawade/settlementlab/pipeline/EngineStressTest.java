package com.siddheshfulawade.settlementlab.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.CasLedger;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The whole pipeline under load: submitters, a small queue, duplicates, both modes, both ledgers. */
class EngineStressTest {

    private static final int SUBMITTERS = 8;
    private static final int TRANSFERS_PER_SUBMITTER = 5_000;
    private static final int ACCOUNTS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    static Stream<Arguments> modesAndLedgers() {
        Function<Map<AccountId, Money>, Ledger> locking = LockingLedger::new;
        Function<Map<AccountId, Money>, Ledger> cas = CasLedger::new;
        return Stream.of(ThreadMode.values())
                .flatMap(mode -> Stream.of(Arguments.of(mode, "LOCKING", locking), Arguments.of(mode, "CAS", cas)));
    }

    @ParameterizedTest(name = "{0} threads, {1} ledger")
    @MethodSource("modesAndLedgers")
    @DisplayName("REQ-LAB-005 REQ-LAB-008 REQ-LAB-009 every invariant holds after a loaded run with duplicates")
    void invariantsHoldUnderLoad(ThreadMode mode, String ledgerName, Function<Map<AccountId, Money>, Ledger> factory)
            throws Exception {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= ACCOUNTS; id++) {
            opening.put(AccountId.of(id), Money.of(10_000));
        }
        Ledger ledger = factory.apply(opening);
        Set<TransferId> posted = ConcurrentHashMap.newKeySet();
        Map<TransferId, Outcome> firstAnswer = new ConcurrentHashMap<>();

        try (var engine = new SettlementEngine(ledger, EngineConfig.of(mode, 4, 256), SettlementListener.NONE)) {
            Concurrently.run(SUBMITTERS, TIMEOUT, submitter -> {
                var random = new SplittableRandom(1_000L + submitter);
                for (int i = 0; i < TRANSFERS_PER_SUBMITTER; i++) {
                    // About one in ten re-sends an id another submitter also uses: real duplicates.
                    String id = random.nextInt(10) == 0 ? "shared-" + i : "t-" + submitter + "-" + i;
                    Transfer transfer = new Transfer(
                            TransferId.of(id),
                            AccountId.of(random.nextLong(1, ACCOUNTS + 1)),
                            AccountId.of(random.nextLong(1, ACCOUNTS + 1)),
                            Money.of(random.nextLong(1, 2_000)));
                    Outcome outcome = await(engine.submit(transfer));
                    if (outcome == Outcome.POSTED) {
                        posted.add(transfer.id());
                    }
                    Outcome earlier = firstAnswer.putIfAbsent(transfer.id(), outcome);
                    // A duplicate sees the first outcome, except after QUEUE_FULL, which is released.
                    if (earlier != null && earlier != Outcome.QUEUE_FULL && outcome != Outcome.QUEUE_FULL) {
                        assertThat(outcome).as("answer for %s", id).isEqualTo(earlier);
                    }
                }
            });
            assertThat(engine.drain(TIMEOUT)).isTrue();

            InvariantReport report =
                    InvariantChecker.checkLedger(opening, engine.snapshot(), engine.postings(), posted);
            assertThat(report.allPassed()).as(report.toString()).isTrue();
            assertThat(engine.metrics().duplicates()).isPositive();
            assertThat(engine.metrics().submitted()).isEqualTo((long) SUBMITTERS * TRANSFERS_PER_SUBMITTER);
        }
    }

    private static Outcome await(java.util.concurrent.CompletionStage<Outcome> stage) {
        try {
            return stage.toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
