package com.siddheshfulawade.settlementlab.runner;

import java.util.List;
import java.util.StringJoiner;

/** Writes results as JSON by hand: a dozen fields do not justify a JSON library (ADR-0001). */
public final class JsonWriter {

    private JsonWriter() {}

    public static String write(Environment env, List<ScenarioResult> results) {
        StringJoiner rows = new StringJoiner(",\n", "[\n", "\n  ]");
        for (ScenarioResult r : results) {
            rows.add("    {"
                    + field("ledger", r.ledger()) + ", "
                    + field("threads", r.threads()) + ", "
                    + field("faults", r.faults()) + ", "
                    + field("transfers", r.transfers()) + ", "
                    + field("throughputPerSecond", r.throughputPerSecond()) + ", "
                    + field("p50Micros", r.p50Micros()) + ", "
                    + field("p99Micros", r.p99Micros()) + ", "
                    + field("rejected", r.rejected()) + ", "
                    + field("duplicates", r.duplicates()) + ", "
                    + field("retries", r.retries()) + ", "
                    + field("breakerOpens", r.breakerOpens()) + ", "
                    + field("outboxPeak", r.outboxPeak()) + ", "
                    + "\"invariantsPassed\": " + r.invariantsPassed() + ", "
                    + field("firstFailure", r.firstFailure())
                    + "}");
        }
        return "{\n"
                + "  \"machine\": {"
                + field("jdk", env.jdk()) + ", "
                + field("cpu", env.cpu()) + ", "
                + field("cores", env.cores()) + ", "
                + field("os", env.os()) + "},\n"
                + "  \"results\": " + rows + "\n"
                + "}\n";
    }

    private static String field(String name, long value) {
        return "\"" + name + "\": " + value;
    }

    private static String field(String name, String value) {
        return "\"" + name + "\": \"" + escape(value) + "\"";
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
