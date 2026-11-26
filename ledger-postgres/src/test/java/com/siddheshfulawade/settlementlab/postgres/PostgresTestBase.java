package com.siddheshfulawade.settlementlab.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every test that needs a real PostgreSQL (REQ-DB-012).
 *
 * <p>Tagged {@code database}: excluded by default, run with {@code -Pdb}. {@code
 * disabledWithoutDocker} is left false on purpose: with {@code -Pdb} and no Docker these tests FAIL
 * rather than skip, so a missing Docker can never turn the gate into a silent pass.
 *
 * <p>One container per test class; the schema is reset before each test by Flyway clean + migrate.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = false)
public abstract class PostgresTestBase {

    /**
     * The PostgreSQL image, pinned to one exact tag. Keep it equal to the tag pre-pulled in
     * .github/workflows/verify.yml (ImageTagSyncTest checks this), and move its major version with
     * a hosted database's if one is ever added (none: V2-06 was dropped).
     */
    static final String POSTGRES_IMAGE = "postgres:18.7-alpine3.24";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static HikariDataSource pool;

    @BeforeAll
    static void openPool() {
        // The extension has started the container before this runs, so the mapped port is known.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(16);
        pool = new HikariDataSource(config);
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    /** Test only: clean is disabled in production code (see LedgerSchema), enabled here. */
    @BeforeEach
    void resetSchema() {
        Flyway flyway = Flyway.configure()
                .dataSource(pool)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    protected static DataSource dataSource() {
        return pool;
    }
}
