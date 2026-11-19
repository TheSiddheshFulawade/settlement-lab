package com.siddheshfulawade.settlementlab.gl;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.pipeline.EngineConfig;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The engine, a faulty general ledger and the outbox together. Lives in the gl test package because
 * only a test (or the runner) may wire pipeline and gl together; the main code keeps them apart.
 */
class GeneralLedgerEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    static Stream<FaultProfile> profiles() {
        return Stream.of(
                FaultProfile.NONE,
                FaultProfile.FLAKY.withLatency(Duration.ZERO),
                FaultProfile.DUPLICATE_ACKS,
                FaultProfile.outage(Duration.ZERO, Duration.ofMillis(300)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    @org.junit.jupiter.api.DisplayName("REQ-LAB-012 after drain, every posting is in the general ledger exactly once")
    void generalLedgerMatchesPostings(FaultProfile profile) throws Exception {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= 5; id++) {
            opening.put(AccountId.of(id), Money.of(10_000));
        }
        var ledger = new LockingLedger(opening);
        var gl = new FakeGeneralLedger(profile, 7);
        List<TransferId> posted = new ArrayList<>();

        try (var outbox = new OutboxPublisher(gl, Duration.ofMillis(5));
                var engine = new SettlementEngine(
                        ledger, EngineConfig.of(ThreadMode.VIRTUAL, 4, 1_000), SettlementListener.NONE, outbox)) {
            var random = new SplittableRandom(3);
            List<CompletionStage<Outcome>> results = new ArrayList<>();
            List<TransferId> ids = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                var t = new Transfer(
                        TransferId.of("t-" + i),
                        AccountId.of(random.nextLong(1, 6)),
                        AccountId.of(random.nextLong(1, 6)),
                        Money.of(random.nextLong(1, 500)));
                ids.add(t.id());
                results.add(engine.submit(t));
            }
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS) == Outcome.POSTED) {
                    posted.add(ids.get(i));
                }
            }

            assertThat(engine.drain(WAIT)).isTrue();
            assertThat(outbox.drain(WAIT)).isTrue();

            InvariantReport report =
                    InvariantChecker.checkRun(opening, engine.snapshot(), engine.postings(), posted, gl.entries());
            assertThat(report.allPassed()).as(report.toString()).isTrue();
            if (profile.name().equals("OUTAGE")) {
                assertThat(outbox.outboxPeak())
                        .as("the outage filled the outbox")
                        .isPositive();
            }
        }
    }
}
