package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.runner.Environment;
import com.siddheshfulawade.settlementlab.runner.ScenarioResult;
import com.siddheshfulawade.settlementlab.runner.Workload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No database needed: fabricated rows. */
class DbResultsFilesTest {

    @Test
    @DisplayName("REQ-DB-013 results-db.md and results-db.json hold the database rows; results.md is not touched")
    void writesSeparateFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("results.md"), "in-memory");
        var env = new Environment("25", "CPU", 8, "Windows 11 amd64");
        var rows = List.of(
                new ScenarioResult(
                        "POSTGRES_PESSIMISTIC",
                        "VIRTUAL",
                        "NONE",
                        20_000,
                        1_500,
                        900,
                        9_000,
                        3,
                        400,
                        0,
                        0,
                        120,
                        true,
                        ""),
                new ScenarioResult(
                        "POSTGRES_OPTIMISTIC",
                        "PLATFORM",
                        "OUTAGE",
                        20_000,
                        900,
                        1_900,
                        40_000,
                        77,
                        400,
                        5,
                        1,
                        900,
                        true,
                        ""));

        DbResultsFiles.write(dir, env, new Workload(1_000, 20_000, 20, 20, 2, 1), rows);

        assertThat(Files.readString(dir.resolve("results-db.md")))
                .contains("| POSTGRES_PESSIMISTIC | VIRTUAL | NONE | 20,000 | 1,500 |")
                .contains("| POSTGRES_OPTIMISTIC | PLATFORM | OUTAGE |")
                .contains("8 cores");
        assertThat(Files.readString(dir.resolve("results-db.json")))
                .contains("POSTGRES_PESSIMISTIC")
                .contains("POSTGRES_OPTIMISTIC");
        assertThat(Files.readString(dir.resolve("results.md"))).isEqualTo("in-memory");
    }
}
