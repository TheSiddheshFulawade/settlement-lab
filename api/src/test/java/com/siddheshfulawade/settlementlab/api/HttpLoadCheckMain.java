package com.siddheshfulawade.settlementlab.api;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the HTTP load check against a server someone started, for example a local jar.
 *
 * <pre>
 * HttpLoadCheckMain BASE_URL [--operations 1500] [--clients 32] [--seed 20261124]
 *                            [--run-transfers 2000] [--ledger LOCKING|CAS] [--threads PLATFORM|VIRTUAL]
 *                            [--runs-only]
 * </pre>
 *
 * Without {@code --runs-only} the target must be a loopback address: the load sends thousands of
 * transfers and is for a server you own. With {@code --runs-only} any host is allowed, and the check
 * does exactly one run and nothing else (the public service serves only runs, 5 per hour per visitor).
 * Exit code: 0 all checks passed, 1 a check failed or the check could not finish, 2 refused or bad
 * arguments (target, user name in the URL, or a size out of range).
 */
public final class HttpLoadCheckMain {

    private static final Set<String> VALUE_OPTIONS =
            Set.of("operations", "clients", "seed", "run-transfers", "ledger", "threads");

    private static final int MAX_RUN_TRANSFERS = 20_000;

    private HttpLoadCheckMain() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** The whole program except the exit, so a test can call it. */
    static int run(String[] args) {
        HttpLoadCheck.Options options;
        String baseUrl;
        boolean runsOnly;
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("usage: HttpLoadCheckMain BASE_URL [options] [--runs-only]");
            }
            baseUrl = args[0];
            Map<String, String> values = new HashMap<>();
            runsOnly = false;
            for (int i = 1; i < args.length; i++) {
                String name = args[i].replaceFirst("^--", "");
                if (!args[i].startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument " + args[i]);
                } else if (name.equals("runs-only")) {
                    runsOnly = true;
                } else if (VALUE_OPTIONS.contains(name) && i + 1 < args.length) {
                    values.put(name, args[++i]);
                } else {
                    throw new IllegalArgumentException("unknown option or missing value: " + args[i]);
                }
            }
            options = optionsFrom(values, runsOnly);
            requireAllowedTarget(baseUrl, runsOnly);
        } catch (IllegalArgumentException e) {
            System.err.println("REFUSED: " + e.getMessage());
            return 2;
        }

        System.out.println("target " + baseUrl + (runsOnly ? " (runs only: one run, no transfers)" : ""));
        HttpLoadCheck.Report report;
        try {
            report = HttpLoadCheck.run(baseUrl, options);
        } catch (RuntimeException e) {
            System.out.println("ERROR the check could not finish: " + e.getMessage());
            return 1;
        }
        for (CheckResult check : report.checks()) {
            System.out.println((check.passed() ? "PASS " : "FAIL ") + check.name() + " - " + check.message());
        }
        System.out.printf(
                Locale.ROOT,
                "requests %d in %.1f s, statuses %s%n",
                report.requests(),
                report.duration().toMillis() / 1000.0,
                report.statusCounts());
        System.out.println("routes " + report.routeCounts());
        System.out.println(report.passed() ? "RESULT PASS" : "RESULT FAIL");
        return report.passed() ? 0 : 1;
    }

    private static HttpLoadCheck.Options optionsFrom(Map<String, String> v, boolean runsOnly) {
        HttpLoadCheck.Options d = HttpLoadCheck.Options.defaults();
        int operations = intOf(v, "operations", d.operations());
        int clients = intOf(v, "clients", d.clients());
        int runTransfers = intOf(v, "run-transfers", d.runTransfers());
        // an empty load would pass every check without proving anything; the server caps a run at 20,000
        if (operations < 1 || clients < 1) {
            throw new IllegalArgumentException("--operations and --clients must each be at least 1");
        }
        if (runTransfers < 1 || runTransfers > MAX_RUN_TRANSFERS) {
            throw new IllegalArgumentException("--run-transfers must be between 1 and " + MAX_RUN_TRANSFERS);
        }
        return new HttpLoadCheck.Options(
                operations,
                clients,
                Long.parseLong(v.getOrDefault("seed", String.valueOf(d.seed()))),
                runTransfers,
                v.getOrDefault("ledger", d.runLedger()),
                v.getOrDefault("threads", d.runThreads()),
                !runsOnly,
                true);
    }

    private static int intOf(Map<String, String> values, String name, int fallback) {
        return values.containsKey(name) ? Integer.parseInt(values.get(name)) : fallback;
    }

    /** Only a loopback host may receive the transfer load; any host may receive the single run. */
    static void requireAllowedTarget(String baseUrl, boolean runsOnly) {
        URI uri;
        String host;
        try {
            uri = URI.create(baseUrl);
            host = uri.getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the base URL is not a valid URL");
        }
        if (host == null) {
            throw new IllegalArgumentException("the base URL needs a host, for example http://localhost:8080");
        }
        if (!runsOnly && !isLoopback(host)) {
            throw new IllegalArgumentException(
                    "%s is not a loopback address; the transfer load is only for a local server. Add --runs-only to do one run."
                            .formatted(host));
        }
        // never echoed: it would put a password in the console and in logs
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("the base URL must not contain a user name or password");
        }
    }

    /** Literal names only, no DNS lookup: a name that merely resolves to 127.0.0.1 is not trusted. */
    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.matches("127(\\.\\d{1,3}){3}") || h.equals("[::1]") || h.equals("::1");
    }
}
