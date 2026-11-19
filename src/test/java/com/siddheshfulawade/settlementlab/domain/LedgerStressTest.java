package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Many threads, few accounts: maximum contention. Every test ends by checking the invariants. */
class LedgerStressTest {

    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 20_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long SEED = 20_261_119L;

    @ParameterizedTest
    @EnumSource(LedgerKind.class)
    @DisplayName("REQ-LAB-002 REQ-LAB-005 random transfers on 10 hot accounts keep every invariant")
    void randomTransfersKeepInvariants(LedgerKind kind) throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(10, 10_000);
        Ledger ledger = kind.create(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        Concurrently.run(THREADS, TIMEOUT, thread -> {
            var random = new SplittableRandom(SEED + thread);
            for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                Transfer t = transfer(
                        "t-" + thread + "-" + i,
                        AccountId.of(random.nextLong(1, 11)),
                        AccountId.of(random.nextLong(1, 11)),
                        random.nextLong(1, 3_000));
                if (ledger.apply(t) == Outcome.POSTED) {
                    accepted.add(t.id());
                }
            }
        });

        InvariantReport report = InvariantChecker.checkLedger(opening, ledger.snapshot(), ledger.postings(), accepted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
        assertThat(accepted).as("the workload posts a real share of transfers").hasSizeGreaterThan(10_000);
    }

    @ParameterizedTest
    @EnumSource(LedgerKind.class)
    @DisplayName("REQ-LAB-006 opposing transfers A to B and B to A do not deadlock")
    void opposingTransfersDoNotDeadlock(LedgerKind kind) throws InterruptedException {
        Map<AccountId, Money> opening = Map.of(A, Money.of(1_000), B, Money.of(1_000));
        Ledger ledger = kind.create(opening);
        LongAdder posted = new LongAdder();

        Concurrently.run(THREADS, TIMEOUT, thread -> {
            boolean forward = thread % 2 == 0;
            for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                Transfer t = forward
                        ? transfer("ab-" + thread + "-" + i, A, B, 1)
                        : transfer("ba-" + thread + "-" + i, B, A, 1);
                if (ledger.apply(t) == Outcome.POSTED) {
                    posted.increment();
                }
            }
        });

        assertThat(Fixtures.total(ledger.snapshot())).isEqualTo(2_000);
        assertThat(ledger.postings()).hasSize(posted.intValue());
    }

    @ParameterizedTest
    @EnumSource(LedgerKind.class)
    @DisplayName("REQ-LAB-006 a ring of transfers (1 to 2, 2 to 3, ... 8 to 1) does not deadlock")
    void ringOfTransfersDoesNotDeadlock(LedgerKind kind) throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(THREADS, 1_000);
        Ledger ledger = kind.create(opening);

        Concurrently.run(THREADS, TIMEOUT, thread -> {
            AccountId source = AccountId.of(thread + 1);
            AccountId target = AccountId.of((thread + 1) % THREADS + 1);
            for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                ledger.apply(transfer("ring-" + thread + "-" + i, source, target, 1));
            }
        });

        assertThat(Fixtures.total(ledger.snapshot())).isEqualTo(THREADS * 1_000L);
    }

    @ParameterizedTest
    @EnumSource(LedgerKind.class)
    @DisplayName("REQ-LAB-005 every snapshot taken while transfers run has the opening total")
    void snapshotsDuringTheRunAreConsistent(LedgerKind kind) throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(10, 10_000);
        long openingTotal = Fixtures.total(opening);
        Ledger ledger = kind.create(opening);
        AtomicInteger workersDone = new AtomicInteger();
        Queue<Long> badTotals = new ConcurrentLinkedQueue<>();
        LongAdder snapshots = new LongAdder();

        // Threads 0..THREADS-1 transfer; the last thread samples snapshots until they finish.
        Concurrently.run(THREADS + 1, TIMEOUT, thread -> {
            if (thread == THREADS) {
                do {
                    long total = Fixtures.total(ledger.snapshot());
                    snapshots.increment();
                    if (total != openingTotal) {
                        badTotals.add(total);
                    }
                } while (workersDone.get() < THREADS);
                return;
            }
            try {
                var random = new SplittableRandom(SEED + thread);
                for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    ledger.apply(transfer(
                            "s-" + thread + "-" + i,
                            AccountId.of(random.nextLong(1, 11)),
                            AccountId.of(random.nextLong(1, 11)),
                            random.nextLong(1, 3_000)));
                }
            } finally {
                workersDone.incrementAndGet();
            }
        });

        assertThat(badTotals)
                .as("snapshot totals that differ from %,d", openingTotal)
                .isEmpty();
        assertThat(snapshots.sum()).isPositive();
    }
}
