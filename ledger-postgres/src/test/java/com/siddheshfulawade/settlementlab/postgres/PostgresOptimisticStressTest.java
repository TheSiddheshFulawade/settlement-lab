package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LedgerStressContract;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;

/**
 * REQ-DB-001, 004: the PostgreSQL ledger in optimistic mode passes the same stress suite as the
 * in-memory ledgers, including opposing transfers and a ring of transfers (conflicts retry, no lost update).
 *
 * <p>Java has single inheritance and the contract is the base class, so this class cannot also extend
 * PostgresTestBase; it uses {@link SharedPostgres} (lazy container, 16-connection pool: the 8 workers
 * plus the sampling thread, because a worker holds one connection per transfer, SPEC.md 16.3) and
 * {@link LedgerSeed} resets the tables in every {@code newLedger}.
 */
@Tag("database")
class PostgresOptimisticStressTest extends LedgerStressContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        LedgerSeed.reset(SharedPostgres.dataSource(), openingBalances);
        return new PostgresLedger(SharedPostgres.dataSource(), LockMode.OPTIMISTIC);
    }

    /**
     * 8 threads x 500 = 4,000 transfers per test instead of the in-memory 160,000: each transfer is
     * about seven round trips and a commit, and the hot-row tests (opposing, ring) serialise on one or
     * two rows. Still far more overlap than needed to expose a wrong lock order or a lost update.
     */
    @Override
    protected int transfersPerThread() {
        return 500;
    }

    @Override
    protected Duration timeout() {
        // Expected: seconds per test on a CI runner. The limit only turns a hang into a failure.
        return Duration.ofMinutes(2);
    }
}
