package com.siddheshfulawade.settlementlab.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the jqwik test classes of this module.
 *
 * <p>Why not {@link PostgresTestBase}: that base relies on JUnit Jupiter extensions
 * ({@code @Testcontainers}, {@code @BeforeEach}), and jqwik's own engine does not run them, so a
 * class with {@code @Property} methods would find no container and no schema. Here the container
 * starts lazily on first use, the schema is migrated once, and the testcontainers reaper removes
 * the container when the JVM ends. Tests reset the data themselves (see {@link LedgerSeed}).
 */
final class SharedPostgres {

    private static HikariDataSource pool;

    private SharedPostgres() {}

    /** Pool of 16 connections: at least the 8 workers plus the sampling thread of the stress suite. */
    static synchronized DataSource dataSource() {
        if (pool == null) {
            PostgreSQLContainer container = new PostgreSQLContainer(PostgresTestBase.POSTGRES_IMAGE);
            container.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(container.getJdbcUrl());
            config.setUsername(container.getUsername());
            config.setPassword(container.getPassword());
            config.setMaximumPoolSize(16);
            pool = new HikariDataSource(config);
            LedgerSchema.migrate(pool);
        }
        return pool;
    }
}
