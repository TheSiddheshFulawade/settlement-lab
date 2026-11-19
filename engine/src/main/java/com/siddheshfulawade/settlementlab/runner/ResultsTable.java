package com.siddheshfulawade.settlementlab.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formats results as a console table and as Markdown (REQ-LAB-015). Numbers in international format. */
public final class ResultsTable {

    static final List<String> HEADERS = List.of(
            "Ledger",
            "Threads",
            "Faults",
            "Transfers",
            "Throughput/s",
            "p50 µs",
            "p99 µs",
            "Rejected",
            "Duplicates",
            "Retries",
            "Breaker opens",
            "Outbox peak",
            "Invariants");

    private ResultsTable() {}

    static String machine(Environment env) {
        return "Machine: " + env.cpu() + ", " + env.cores() + " cores, " + env.os() + ", JDK " + env.jdk();
    }

    static List<String> cells(ScenarioResult r) {
        return List.of(
                r.ledger(),
                r.threads(),
                r.faults(),
                number(r.transfers()),
                number(r.throughputPerSecond()),
                number(r.p50Micros()),
                number(r.p99Micros()),
                number(r.rejected()),
                number(r.duplicates()),
                number(r.retries()),
                number(r.breakerOpens()),
                number(r.outboxPeak()),
                r.invariantsPassed() ? "PASS" : "FAIL");
    }

    public static String markdown(Environment env, List<ScenarioResult> results) {
        StringBuilder md = new StringBuilder();
        md.append(machine(env)).append("\n\n");
        md.append("| ").append(String.join(" | ", HEADERS)).append(" |\n");
        md.append("|").append("---|".repeat(HEADERS.size())).append('\n');
        for (ScenarioResult r : results) {
            md.append("| ").append(String.join(" | ", cells(r))).append(" |\n");
        }
        appendFailures(md, results);
        return md.toString();
    }

    public static String text(Environment env, List<ScenarioResult> results) {
        List<List<String>> rows = new ArrayList<>();
        // Consoles without UTF-8 print "µ" as "?", so the console header says "us".
        rows.add(HEADERS.stream().map(h -> h.replace("µs", "us")).toList());
        results.forEach(r -> rows.add(cells(r)));
        int[] widths = new int[HEADERS.size()];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        StringBuilder out = new StringBuilder(machine(env)).append("\n\n");
        for (List<String> row : rows) {
            List<String> padded = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                padded.add(c < 3 ? pad(row.get(c), widths[c], false) : pad(row.get(c), widths[c], true));
            }
            out.append(String.join("  ", padded)).append('\n');
        }
        appendFailures(out, results);
        return out.toString();
    }

    private static void appendFailures(StringBuilder out, List<ScenarioResult> results) {
        for (ScenarioResult r : results) {
            if (!r.invariantsPassed()) {
                out.append("\n- ")
                        .append(r.ledger())
                        .append(" / ")
                        .append(r.threads())
                        .append(" / ")
                        .append(r.faults())
                        .append(": ")
                        .append(r.firstFailure());
            }
        }
    }

    private static String pad(String value, int width, boolean right) {
        String spaces = " ".repeat(width - value.length());
        return right ? spaces + value : value + spaces;
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
