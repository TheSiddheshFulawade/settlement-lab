package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import com.siddheshfulawade.settlementlab.runner.Environment;
import com.siddheshfulawade.settlementlab.runner.ResultsTable;
import com.siddheshfulawade.settlementlab.runner.RunnerSettings;
import com.siddheshfulawade.settlementlab.runner.ScenarioResult;
import com.siddheshfulawade.settlementlab.runner.ScenarioRunner;
import com.siddheshfulawade.settlementlab.runner.Workload;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Local only (needs Docker; not run in CI): the database cells {POSTGRES_PESSIMISTIC,
 * POSTGRES_OPTIMISTIC} x threads x faults on a smaller workload. Writes results/results-db.json and
 * results-db.md. Options: --accounts 1000 --transfers 20000 --hot-accounts 20 --hot-percent 20
 * --duplicate-percent 2 --seed 20261119 --out results.
 */
public final class DbResultsMain {

    private DbResultsMain() {}

    public static void main(String[] args) {
        // A misspelled option must fail, not silently run the defaults and mislabel committed results.
        Set<String> known =
                Set.of("accounts", "transfers", "hot-accounts", "hot-percent", "duplicate-percent", "seed", "out");
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("options come in --name value pairs");
        }
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i].replaceFirst("^--", "");
            if (!args[i].startsWith("--") || !known.contains(name)) {
                throw new IllegalArgumentException("unknown option " + args[i] + "; known: " + known);
            }
            o.put(name, args[i + 1]);
        }
        Workload workload = new Workload(
                num(o, "accounts", 1_000),
                num(o, "transfers", 20_000),
                num(o, "hot-accounts", 20),
                num(o, "hot-percent", 20),
                num(o, "duplicate-percent", 2),
                Long.parseLong(o.getOrDefault("seed", "20261119")));
        Path out = Path.of(o.getOrDefault("out", "results"));
        RunnerSettings settings = RunnerSettings.defaults();
        Environment env = Environment.detect();
        List<FaultProfile> faults = List.of(
                FaultProfile.NONE,
                FaultProfile.FLAKY,
                FaultProfile.outage(Duration.ofMillis(50), Duration.ofSeconds(1)));

        List<ScenarioResult> results = new ArrayList<>();
        try (PostgreSQLContainer container = new PostgreSQLContainer(PostgresTestBase.POSTGRES_IMAGE)) {
            container.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(container.getJdbcUrl());
            config.setUsername(container.getUsername());
            config.setPassword(container.getPassword());
            config.setMaximumPoolSize(Math.max(16, settings.workers() + 4));
            try (HikariDataSource pool = new HikariDataSource(config)) {
                LedgerSchema.migrate(pool);
                for (LockMode mode : LockMode.values()) {
                    for (ThreadMode threads : ThreadMode.values()) {
                        for (FaultProfile fault : faults) {
                            System.out.printf("Running POSTGRES_%s / %s / %s ...%n", mode, threads, fault);
                            results.add(new ScenarioRunner()
                                    .run(new PostgresLedgerFactory(pool, mode), threads, fault, workload, settings));
                        }
                    }
                }
            }
        }
        System.out.print(ResultsTable.text(env, results));
        DbResultsFiles.write(out, env, workload, results);
        if (results.stream().anyMatch(r -> !r.invariantsPassed())) {
            System.exit(1);
        }
    }

    private static int num(Map<String, String> o, String name, int fallback) {
        return Integer.parseInt(
                o.getOrDefault(name, String.valueOf(fallback)).replace(",", "").replace("_", ""));
    }
}
