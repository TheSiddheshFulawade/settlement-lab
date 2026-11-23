package com.siddheshfulawade.settlementlab.domain;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.testing.Concurrently;
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
import org.junit.jupiter.api.Test;

/**
 * Many threads, few accounts: maximum contention. Every test ends by checking the invariants.
 * Extended by one concrete test class per ledger (engine in-memory ledgers now, PostgreSQL ledgers
 * through the engine test-jar later). A subclass builds its ledger and may widen the timeout.
 */
public abstract class LedgerStressContract {

    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 20_000;
    private static final long SEED = 20_261_119L;

    /** A fresh ledger holding exactly these opening balances. */
    protected abstract Ledger newLedger(Map<AccountId, Money> openingBalances);

    /** How long each stress run may take; a slower ledger (a database) overrides this. */
    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /**
     * Transfers each worker thread submits per test. A ledger behind a database overrides this with a
     * smaller number, because every transfer is several network round trips; the in-memory ledgers
     * keep the full load.
     */
    protected int transfersPerThread() {
        return TRANSFERS_PER_THREAD;
    }

    @Test
    @DisplayName("REQ-LAB-002 REQ-LAB-005 random transfers on 10 hot accounts keep every invariant")
    public void randomTransfersKeepInvariants() throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(10, 10_000);
        Ledger ledger = newLedger(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        Concurrently.run(THREADS, timeout(), thread -> {
            var random = new SplittableRandom(SEED + thread);
            for (int i = 0; i < transfersPerThread(); i++) {
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

        assertAllInvariants(opening, ledger, accepted);
        // One in sixteen: 10,000 of the full in-memory load of 160,000, scaled with transfersPerThread.
        assertThat(accepted)
                .as("the workload posts a real share of transfers")
                .hasSizeGreaterThan(THREADS * transfersPerThread() / 16);
    }

    @Test
    @DisplayName("REQ-LAB-006 opposing transfers A to B and B to A do not deadlock")
    public void opposingTransfersDoNotDeadlock() throws InterruptedException {
        Map<AccountId, Money> opening = Map.of(A, Money.of(1_000), B, Money.of(1_000));
        Ledger ledger = newLedger(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        Concurrently.run(THREADS, timeout(), thread -> {
            boolean forward = thread % 2 == 0;
            for (int i = 0; i < transfersPerThread(); i++) {
                Transfer t = forward
                        ? transfer("ab-" + thread + "-" + i, A, B, 1)
                        : transfer("ba-" + thread + "-" + i, B, A, 1);
                if (ledger.apply(t) == Outcome.POSTED) {
                    accepted.add(t.id());
                }
            }
        });

        assertAllInvariants(opening, ledger, accepted);
    }

    @Test
    @DisplayName("REQ-LAB-006 a ring of transfers (1 to 2, 2 to 3, ... 8 to 1) does not deadlock")
    public void ringOfTransfersDoesNotDeadlock() throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(THREADS, 1_000);
        Ledger ledger = newLedger(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        Concurrently.run(THREADS, timeout(), thread -> {
            AccountId source = AccountId.of(thread + 1);
            AccountId target = AccountId.of((thread + 1) % THREADS + 1);
            for (int i = 0; i < transfersPerThread(); i++) {
                Transfer t = transfer("ring-" + thread + "-" + i, source, target, 1);
                if (ledger.apply(t) == Outcome.POSTED) {
                    accepted.add(t.id());
                }
            }
        });

        assertAllInvariants(opening, ledger, accepted);
    }

    @Test
    @DisplayName("REQ-LAB-005 every snapshot taken while transfers run has the opening total")
    public void snapshotsDuringTheRunAreConsistent() throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(10, 10_000);
        long openingTotal = Fixtures.total(opening);
        Ledger ledger = newLedger(opening);
        AtomicInteger workersDone = new AtomicInteger();
        Queue<Long> badTotals = new ConcurrentLinkedQueue<>();
        LongAdder snapshots = new LongAdder();

        // Threads 0..THREADS-1 transfer; the last thread samples snapshots until they finish.
        Concurrently.run(THREADS + 1, timeout(), thread -> {
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
                for (int i = 0; i < transfersPerThread(); i++) {
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

    @Test
    @DisplayName("REQ-API-004 single-account reads during the run never see a negative or torn balance")
    public void balanceReadsDuringTheRunAreSane() throws InterruptedException {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(4, 10_000);
        Ledger ledger = newLedger(opening);
        AtomicInteger workersDone = new AtomicInteger();
        Queue<Long> bad = new ConcurrentLinkedQueue<>();

        Concurrently.run(THREADS + 1, timeout(), thread -> {
            if (thread == THREADS) {
                do {
                    for (long id = 1; id <= 4; id++) {
                        long units =
                                ledger.balance(AccountId.of(id)).orElseThrow().minorUnits();
                        if (units < 0 || units > 40_000) {
                            bad.add(units);
                        }
                    }
                } while (workersDone.get() < THREADS);
                return;
            }
            try {
                var random = new SplittableRandom(SEED + thread);
                for (int i = 0; i < transfersPerThread(); i++) {
                    ledger.apply(transfer(
                            "b-" + thread + "-" + i,
                            AccountId.of(random.nextLong(1, 5)),
                            AccountId.of(random.nextLong(1, 5)),
                            random.nextLong(1, 3_000)));
                }
            } finally {
                workersDone.incrementAndGet();
            }
        });

        assertThat(bad).isEmpty();
    }

    /**
     * The total alone is not enough: a lost update (a whole transfer silently dropped) still conserves
     * the total. Replaying the postings over the opening balances is what catches it.
     */
    private static void assertAllInvariants(Map<AccountId, Money> opening, Ledger ledger, Set<TransferId> accepted) {
        InvariantReport report = InvariantChecker.checkLedger(opening, ledger.snapshot(), ledger.postings(), accepted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
    }
}
