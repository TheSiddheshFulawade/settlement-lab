package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the scenario matrix (ledger {LOCKING, CAS} × threads {PLATFORM, VIRTUAL} × faults {NONE,
 * FLAKY, OUTAGE}) on one seeded workload, prints the results table with the machine, and writes
 * {@code results/results.json} and {@code results/results.md} (REQ-LAB-015, SPEC.md section 4.6).
 *
 * <p>Options (all optional): {@code --accounts 10000 --transfers 100000 --hot-accounts 20
 * --hot-percent 20 --duplicate-percent 2 --seed 20261119 --out results}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        Workload workload = new Workload(
                intOption(options, "accounts", 10_000),
                intOption(options, "transfers", 100_000),
                intOption(options, "hot-accounts", 20),
                intOption(options, "hot-percent", 20),
                intOption(options, "duplicate-percent", 2),
                Long.parseLong(options.getOrDefault("seed", "20261119")));
        Path out = Path.of(options.getOrDefault("out", "results"));
        RunnerSettings settings = RunnerSettings.defaults();
        Environment env = Environment.detect();

        List<FaultProfile> faults = List.of(
                FaultProfile.NONE,
                FaultProfile.FLAKY,
                FaultProfile.outage(Duration.ofMillis(50), Duration.ofSeconds(1)));
        List<ScenarioResult> results = new ArrayList<>();
        System.out.println(ResultsTable.machine(env));
        System.out.printf("Workload: %s%n%n", workload);
        warmUp(workload, settings);
        for (LedgerType ledger : LedgerType.values()) {
            for (ThreadMode threads : ThreadMode.values()) {
                for (FaultProfile fault : faults) {
                    System.out.printf("Running %s / %s / %s ...%n", ledger, threads, fault);
                    results.add(new ScenarioRunner().run(new Scenario(ledger, threads, fault, workload, settings)));
                }
            }
        }

        System.out.println();
        System.out.print(ResultsTable.text(env, results));
        write(out.resolve("results.json"), JsonWriter.write(env, results));
        write(
                out.resolve("results.md"),
                "# Results\n\nWorkload: `" + workload + "`\n\n" + ResultsTable.markdown(env, results));
        System.out.printf("%nWrote %s and %s%n", out.resolve("results.json"), out.resolve("results.md"));
        if (results.stream().anyMatch(r -> !r.invariantsPassed())) {
            System.exit(1);
        }
    }

    /**
     * One short unreported run per ledger first, so the JIT has compiled the hot paths before the
     * first measured cell; otherwise whichever cell runs first looks slower.
     */
    private static void warmUp(Workload workload, RunnerSettings settings) {
        Workload small = new Workload(
                workload.accounts(),
                Math.min(20_000, workload.transfers()),
                workload.hotAccounts(),
                workload.hotPercent(),
                workload.duplicatePercent(),
                workload.seed() + 1);
        for (LedgerType ledger : LedgerType.values()) {
            System.out.printf("Warm-up %s ...%n", ledger);
            new ScenarioRunner().run(new Scenario(ledger, ThreadMode.VIRTUAL, FaultProfile.NONE, small, settings));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --name value, got " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        return Integer.parseInt(options.getOrDefault(name, String.valueOf(fallback))
                .replace(",", "")
                .replace("_", ""));
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
