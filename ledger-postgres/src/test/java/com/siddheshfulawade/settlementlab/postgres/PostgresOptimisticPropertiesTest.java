package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LedgerPropertiesContract;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.util.Map;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.Tag;

/**
 * REQ-DB-001: the PostgreSQL ledger in optimistic mode passes the same property suite as the
 * in-memory ledgers.
 *
 * <p>Implementer contract: {@code new PostgresLedger(DataSource, LockMode.OPTIMISTIC)} over a schema
 * that already exists (Flyway) and an {@code account} table already holding the opening balances. The
 * constructor must not create or seed anything. Postings are numbered from 1 in the order they commit.
 *
 * <p>jqwik calls {@code newLedger} once per try, so every call wipes and re-seeds the tables. Tries
 * are lowered from jqwik's default (1000) because each transfer is several database round trips.
 * {@code @Tag} is jqwik's own ({@code net.jqwik.api.Tag}): the jqwik engine ignores Jupiter's, and
 * without it this class would run, and fail, in the default build without Docker. The jqwik engine
 * does not run the Jupiter extensions, hence {@link SharedPostgres} instead of PostgresTestBase.
 */
@Tag("database")
@PropertyDefaults(tries = 40)
class PostgresOptimisticPropertiesTest extends LedgerPropertiesContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        LedgerSeed.reset(SharedPostgres.dataSource(), openingBalances);
        return new PostgresLedger(SharedPostgres.dataSource(), LockMode.OPTIMISTIC);
    }
}
