package com.siddheshfulawade.settlementlab.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.ApplyResult;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** REQ-DB-004a at the engine: REJECTED_CONTENTION is answered, counted, and frees the id. */
class SettlementEngineContentionTest {

    private static final AccountId A = AccountId.of(1);
    private static final AccountId B = AccountId.of(2);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private SettlementEngine engine;

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /** Answers REJECTED_CONTENTION for the first {@code contended} calls, then behaves like a real ledger. */
    private static final class ContendedLedger implements Ledger {
        private final LockingLedger real = new LockingLedger(Map.of(A, Money.of(1_000), B, Money.of(1_000)));
        private final AtomicInteger remaining;
        final AtomicInteger calls = new AtomicInteger();

        ContendedLedger(int contended) {
            remaining = new AtomicInteger(contended);
        }

        @Override
        public ApplyResult applyWithResult(Transfer transfer) {
            calls.incrementAndGet();
            if (remaining.getAndDecrement() > 0) {
                return ApplyResult.rejected(Outcome.REJECTED_CONTENTION);
            }
            return real.applyWithResult(transfer);
        }

        @Override
        public boolean hasAccount(AccountId id) {
            return real.hasAccount(id);
        }

        @Override
        public Optional<Money> balance(AccountId id) {
            return real.balance(id);
        }

        @Override
        public Map<AccountId, Money> snapshot() {
            return real.snapshot();
        }

        @Override
        public List<Posting> postings() {
            return real.postings();
        }
    }

    private static Transfer transfer(String id) {
        return new Transfer(TransferId.of(id), A, B, Money.of(10));
    }

    private static Outcome outcome(CompletionStage<Outcome> stage) throws Exception {
        return stage.toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-DB-004a contention is answered to the caller and the same id is then processed fresh")
    void contentionReleasesTheClaim(ThreadMode mode) throws Exception {
        var ledger = new ContendedLedger(1);
        engine = new SettlementEngine(ledger, EngineConfig.of(mode, 2, 10), SettlementListener.NONE);

        Outcome first = outcome(engine.submit(transfer("t-1")));
        Outcome retry = outcome(engine.submit(transfer("t-1")));

        assertThat(first).isEqualTo(Outcome.REJECTED_CONTENTION);
        assertThat(retry)
                .as("the claim was released, so the retry is not a replay")
                .isEqualTo(Outcome.POSTED);
        assertThat(ledger.calls).hasValue(2);
        assertThat(engine.postings()).hasSize(1);
        assertThat(engine.metrics().duplicates())
                .as("the retry is not a duplicate")
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-DB-004a contention is counted in rejectedContention, separately from failures and queue-full")
    void contentionIsCounted(ThreadMode mode) throws Exception {
        engine = new SettlementEngine(new ContendedLedger(2), EngineConfig.of(mode, 1, 10), SettlementListener.NONE);

        outcome(engine.submit(transfer("a")));
        outcome(engine.submit(transfer("b")));
        outcome(engine.submit(transfer("c")));

        EngineMetrics metrics = engine.metrics();
        assertThat(metrics.rejectedContention()).isEqualTo(2);
        assertThat(metrics.posted()).isEqualTo(1);
        assertThat(metrics.failed()).isZero();
        assertThat(metrics.queueFull()).isZero();
        assertThat(engine.drain(WAIT)).isTrue();
    }

    @Test
    @DisplayName("REQ-DB-004a a contention answer posts nothing and is not published")
    void contentionPublishesNothing() throws Exception {
        List<Posting> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine = new SettlementEngine(
                new ContendedLedger(1),
                EngineConfig.of(ThreadMode.PLATFORM, 1, 10),
                SettlementListener.NONE,
                published::add);

        assertThat(outcome(engine.submit(transfer("t-1")))).isEqualTo(Outcome.REJECTED_CONTENTION);

        assertThat(engine.drain(WAIT)).isTrue();
        assertThat(published).isEmpty();
        assertThat(engine.postings()).isEmpty();
    }
}
