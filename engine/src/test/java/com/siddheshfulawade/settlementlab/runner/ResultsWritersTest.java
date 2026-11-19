package com.siddheshfulawade.settlementlab.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultsWritersTest {

    private static final Environment MACHINE = new Environment("21.0.13", "Example CPU \"X\"", 4, "Linux amd64");
    private static final ScenarioResult ROW = new ScenarioResult(
            "LOCKING", "VIRTUAL", "FLAKY", 100_000, 123_456, 1_200, 45_000, 321, 4_987, 77, 1, 250, true, "");

    @Test
    @DisplayName("REQ-LAB-015 the Markdown table has the documented columns and international numbers")
    void markdownTable() {
        String md = ResultsTable.markdown(MACHINE, List.of(ROW));

        assertThat(md)
                .contains("| Ledger | Threads | Faults | Transfers | Throughput/s | p50 µs | p99 µs | Rejected"
                        + " | Duplicates | Retries | Breaker opens | Outbox peak | Invariants |")
                .contains(
                        "| LOCKING | VIRTUAL | FLAKY | 100,000 | 123,456 | 1,200 | 45,000 | 321 | 4,987 | 77 | 1 | 250 | PASS |")
                .contains("JDK 21.0.13")
                .contains("4 cores");
    }

    @Test
    @DisplayName("REQ-LAB-015 the console table lines up its columns")
    void consoleTable() {
        String text = ResultsTable.text(MACHINE, List.of(ROW));

        List<String> lines = text.lines()
                .filter(l -> l.startsWith("LOCKING") || l.startsWith("Ledger"))
                .toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).length()).isEqualTo(lines.get(1).length());
    }

    @Test
    @DisplayName("REQ-LAB-015 the JSON holds the machine and one object per row, with strings escaped")
    void json() {
        String json = JsonWriter.write(MACHINE, List.of(ROW));

        assertThat(json)
                .contains("\"jdk\": \"21.0.13\"")
                .contains("\"cpu\": \"Example CPU \\\"X\\\"\"")
                .contains("\"ledger\": \"LOCKING\"")
                .contains("\"throughputPerSecond\": 123456")
                .contains("\"invariantsPassed\": true");
        assertThat(json.trim()).startsWith("{").endsWith("}");
    }

    @Test
    @DisplayName("a failed invariant is shown as FAIL with its first counterexample")
    void failedInvariant() {
        ScenarioResult failed = new ScenarioResult(
                "CAS", "PLATFORM", "NONE", 10, 1, 1, 1, 0, 0, 0, 0, 0, false, "FAIL TOTAL_CONSERVED: x");

        assertThat(ResultsTable.markdown(MACHINE, List.of(failed)))
                .contains("| FAIL |")
                .contains("TOTAL_CONSERVED: x");
    }
}
