package com.siddheshfulawade.settlementlab.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvalidReason;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SettlementEngineTest {

    private static final AccountId A = AccountId.of(1);
    private static final AccountId B = AccountId.of(2);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<SettlementEngine> engines = new ArrayList<>();

    @AfterEach
    void closeEngines() {
        engines.forEach(SettlementEngine::close);
    }

    private SettlementEngine engine(Ledger ledger, EngineConfig config, SettlementListener listener) {
        SettlementEngine engine = new SettlementEngine(ledger, config, listener);
        engines.add(engine);
        return engine;
    }

    private static LockingLedger ledger() {
        return new LockingLedger(Map.of(A, Money.of(1_000), B, Money.of(1_000)));
    }

    private static Transfer transfer(String id, AccountId from, AccountId to, long amount) {
        return new Transfer(TransferId.of(id), from, to, Money.of(amount));
    }

    private static Outcome outcome(CompletionStage<Outcome> stage) throws Exception {
        return stage.toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-002 a valid transfer is queued, applied by a worker and posted")
    void postsValidTransfer(ThreadMode mode) throws Exception {
        var engine = engine(ledger(), EngineConfig.of(mode, 2, 10), SettlementListener.NONE);

        assertThat(outcome(engine.submit(transfer("t-1", A, B, 100)))).isEqualTo(Outcome.POSTED);

        assertThat(engine.snapshot()).containsEntry(A, Money.of(900)).containsEntry(B, Money.of(1_100));
        assertThat(engine.metrics().posted()).isEqualTo(1);
        assertThat(engine.metrics().submitted()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-004 an invalid transfer is rejected before it is queued, and the reason is available")
    void rejectsInvalidBeforeQueueing(ThreadMode mode) throws Exception {
        var gated = new GatedLedger(ledger());
        var engine = engine(gated, EngineConfig.of(mode, 1, 10), SettlementListener.NONE);
        Transfer invalid = transfer("t-1", A, AccountId.of(99), 100);

        CompletionStage<Outcome> result = engine.submit(invalid);

        // Already complete when submit returns, and no worker ever saw it.
        assertThat(result.toCompletableFuture()).isCompletedWithValue(Outcome.REJECTED_INVALID);
        assertThat(gated.entered()).isZero();
        assertThat(engine.validate(invalid)).contains(InvalidReason.UNKNOWN_TARGET);
        assertThat(engine.metrics().rejectedInvalid()).isEqualTo(1);
        gated.open();
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-003 a transfer without funds is counted as rejected for funds")
    void countsRejectedFunds(ThreadMode mode) throws Exception {
        var engine = engine(ledger(), EngineConfig.of(mode, 1, 10), SettlementListener.NONE);

        assertThat(outcome(engine.submit(transfer("t-1", A, B, 5_000)))).isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(engine.metrics().rejectedFunds()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-008 a duplicate id gets the original outcome and is applied once")
    void duplicateGetsOriginalOutcome(ThreadMode mode) throws Exception {
        var engine = engine(ledger(), EngineConfig.of(mode, 2, 10), SettlementListener.NONE);

        Outcome first = outcome(engine.submit(transfer("t-1", A, B, 100)));
        Outcome second = outcome(engine.submit(transfer("t-1", A, B, 100)));

        assertThat(first).isEqualTo(Outcome.POSTED);
        assertThat(second).isEqualTo(Outcome.POSTED);
        assertThat(engine.postings()).hasSize(1);
        assertThat(engine.metrics().duplicates()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-009 a full queue answers QUEUE_FULL within the bounded wait; the id can be retried later")
    void fullQueueAnswersQueueFull(ThreadMode mode) throws Exception {
        var gated = new GatedLedger(ledger());
        var config = new EngineConfig(mode, 1, 1, Duration.ofMillis(50), WAIT);
        var engine = engine(gated, config, SettlementListener.NONE);

        CompletionStage<Outcome> running = engine.submit(transfer("t-1", A, B, 1));
        await().atMost(WAIT).until(() -> gated.entered() == 1); // the only worker is now busy
        CompletionStage<Outcome> queued = engine.submit(transfer("t-2", A, B, 1)); // fills the queue

        long start = System.nanoTime();
        CompletionStage<Outcome> rejected = engine.submit(transfer("t-3", A, B, 1));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(rejected.toCompletableFuture()).isCompletedWithValue(Outcome.QUEUE_FULL);
        assertThat(waited).isLessThan(Duration.ofSeconds(2));
        assertThat(engine.metrics().queueFull()).isEqualTo(1);
        assertThat(engine.metrics().queueDepth()).isEqualTo(1);

        gated.open();
        assertThat(outcome(running)).isEqualTo(Outcome.POSTED);
        assertThat(outcome(queued)).isEqualTo(Outcome.POSTED);
        assertThat(outcome(engine.submit(transfer("t-3", A, B, 1)))).isEqualTo(Outcome.POSTED);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-010 at most N transfers are applied at once, on the chosen kind of thread")
    void capsConcurrencyAtN(ThreadMode mode) throws Exception {
        var gated = new GatedLedger(ledger());
        var engine = engine(gated, EngineConfig.of(mode, 3, 100), SettlementListener.NONE);
        List<CompletionStage<Outcome>> results = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            results.add(engine.submit(transfer("t-" + i, A, B, 1)));
        }
        await().atMost(WAIT).until(() -> gated.entered() == 3);
        assertThat(gated.maxRunning()).isEqualTo(3);
        gated.open();

        for (CompletionStage<Outcome> result : results) {
            assertThat(outcome(result)).isEqualTo(Outcome.POSTED);
        }
        assertThat(gated.maxRunning()).isLessThanOrEqualTo(3);
        assertThat(gated.sawVirtualThread()).isEqualTo(mode == ThreadMode.VIRTUAL);
        assertThat(gated.sawPlatformThread()).isEqualTo(mode == ThreadMode.PLATFORM);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("drain waits for queued work; close answers anything left with QUEUE_FULL and refuses new work")
    void drainAndClose(ThreadMode mode) throws Exception {
        var gated = new GatedLedger(ledger());
        var config = new EngineConfig(mode, 1, 10, Duration.ofMillis(50), Duration.ofMillis(200));
        var engine = engine(gated, config, SettlementListener.NONE);

        CompletionStage<Outcome> running = engine.submit(transfer("t-1", A, B, 1));
        await().atMost(WAIT).until(() -> gated.entered() == 1);
        CompletionStage<Outcome> waiting = engine.submit(transfer("t-2", A, B, 1));

        assertThat(engine.drain(Duration.ofMillis(50)))
                .as("cannot drain while the gate is shut")
                .isFalse();

        gated.open();
        assertThat(engine.drain(WAIT)).isTrue();
        assertThat(outcome(running)).isEqualTo(Outcome.POSTED);
        assertThat(outcome(waiting)).isEqualTo(Outcome.POSTED);

        engine.close();
        engine.close(); // idempotent
        assertThatThrownBy(() -> engine.submit(transfer("t-3", A, B, 1))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("close completes work that never ran with QUEUE_FULL, so no caller waits forever")
    void closeCompletesLeftovers(ThreadMode mode) throws Exception {
        var gated = new GatedLedger(ledger());
        var config = new EngineConfig(mode, 1, 10, Duration.ofMillis(50), Duration.ofMillis(100));
        var engine = engine(gated, config, SettlementListener.NONE);

        CompletionStage<Outcome> running = engine.submit(transfer("t-1", A, B, 1));
        await().atMost(WAIT).until(() -> gated.entered() == 1);
        CompletionStage<Outcome> leftover = engine.submit(transfer("t-2", A, B, 1));

        // Open the gate only once close has answered the leftover, so t-2 cannot sneak through.
        Thread.ofVirtual().start(() -> {
            await().atMost(WAIT).until(() -> leftover.toCompletableFuture().isDone());
            gated.open();
        });
        engine.close(); // drain times out after 100 ms with t-2 still queued, then waits for t-1

        assertThat(outcome(leftover)).isEqualTo(Outcome.QUEUE_FULL);
        assertThat(outcome(running)).isEqualTo(Outcome.POSTED);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("the listener hears every outcome, and a failing listener does not stop the engine")
    void listenerHearsOutcomes(ThreadMode mode) throws Exception {
        Queue<String> heard = new ConcurrentLinkedQueue<>();
        SettlementListener listener = (transfer, outcome) -> {
            heard.add(transfer.id() + "=" + outcome);
            if (transfer.id().value().equals("t-1")) {
                throw new IllegalStateException("listener bug");
            }
        };
        var engine = engine(ledger(), EngineConfig.of(mode, 1, 10), listener);

        assertThat(outcome(engine.submit(transfer("t-1", A, B, 1)))).isEqualTo(Outcome.POSTED);
        assertThat(outcome(engine.submit(transfer("t-2", A, B, 5_000)))).isEqualTo(Outcome.REJECTED_FUNDS);

        await().atMost(WAIT).until(() -> heard.size() == 2);
        assertThat(heard).containsExactly("t-1=POSTED", "t-2=REJECTED_FUNDS");
        assertThat(engine.metrics().listenerErrors()).isEqualTo(1);
    }

    // TODO-ME: write `ledgerFailureIsReleasedAndCanBeRetried`: give the engine a Ledger whose apply
    //  throws a RuntimeException for id "boom" the first time only. Check that (1) the future for
    //  "boom" completes exceptionally, (2) metrics().failed() is 1, and (3) submitting "boom" again
    //  posts it. Check it: the test passes; then delete the `idempotency.release(...)` line in
    //  SettlementEngine.process and see part (3) fail.

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-008 concurrent duplicates through the engine post once")
    void concurrentDuplicatesPostOnce(ThreadMode mode) throws Exception {
        var engine = engine(ledger(), EngineConfig.of(mode, 4, 100), SettlementListener.NONE);
        Queue<CompletionStage<Outcome>> results = new ConcurrentLinkedQueue<>();

        Concurrently.run(20, WAIT, thread -> results.add(engine.submit(transfer("same", A, B, 10))));

        for (CompletionStage<Outcome> result : results) {
            assertThat(outcome(result)).isEqualTo(Outcome.POSTED);
        }
        assertThat(engine.postings()).hasSize(1);
        assertThat(engine.metrics().duplicates()).isEqualTo(19);
    }
}
