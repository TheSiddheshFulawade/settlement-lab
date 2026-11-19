package com.siddheshfulawade.settlementlab.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class IdempotencyConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AccountId A = AccountId.of(1);
    private static final AccountId B = AccountId.of(2);

    @RepeatedTest(20)
    @DisplayName("REQ-LAB-008 the same id from 50 threads at once posts once, and all 50 see the same outcome")
    void fiftyConcurrentDuplicatesPostOnce() throws Exception {
        var ledger = new LockingLedger(Map.of(A, Money.of(1_000), B, Money.ZERO));
        var store = new IdempotencyStore();
        var transfer = new Transfer(TransferId.of("t-1"), A, B, Money.of(100));
        AtomicInteger runs = new AtomicInteger();
        Queue<CompletableFuture<Outcome>> results = new ConcurrentLinkedQueue<>();

        Concurrently.run(
                50,
                TIMEOUT,
                thread -> results.add(store.execute(transfer.id(), () -> {
                    runs.incrementAndGet();
                    return ledger.apply(transfer);
                })));

        assertThat(runs).hasValue(1);
        assertThat(ledger.postings()).hasSize(1);
        assertThat(ledger.snapshot()).containsEntry(A, Money.of(900)).containsEntry(B, Money.of(100));
        assertThat(store.duplicates()).isEqualTo(49);
        for (CompletableFuture<Outcome> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(Outcome.POSTED);
        }
    }

    @Test
    @DisplayName("REQ-LAB-008 1,000 ids, each sent by 8 threads, are each posted exactly once")
    void manyIdsEachPostedOnce() throws InterruptedException {
        int ids = 1_000;
        int threads = 8;
        var ledger = new LockingLedger(Map.of(A, Money.of(1_000_000), B, Money.of(1_000_000)));
        var store = new IdempotencyStore();

        Concurrently.run(threads, TIMEOUT, thread -> {
            for (int i = 0; i < ids; i++) {
                // Odd ids go A to B, even ids B to A, so both lock orders are exercised.
                var transfer = i % 2 == 0
                        ? new Transfer(TransferId.of("t-" + i), A, B, Money.of(1))
                        : new Transfer(TransferId.of("t-" + i), B, A, Money.of(1));
                store.execute(transfer.id(), () -> ledger.apply(transfer));
            }
        });

        List<TransferId> all = new ArrayList<>();
        for (int i = 0; i < ids; i++) {
            all.add(TransferId.of("t-" + i));
        }
        var report = InvariantChecker.checkLedger(
                Map.of(A, Money.of(1_000_000), B, Money.of(1_000_000)), ledger.snapshot(), ledger.postings(), all);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
        assertThat(store.size()).isEqualTo(ids);
        assertThat(store.duplicates()).isEqualTo((long) ids * (threads - 1));
    }
}
