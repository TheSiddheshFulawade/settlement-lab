package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.runner.Environment;
import com.siddheshfulawade.settlementlab.runner.JsonWriter;
import com.siddheshfulawade.settlementlab.runner.ResultsTable;
import com.siddheshfulawade.settlementlab.runner.ScenarioResult;
import com.siddheshfulawade.settlementlab.runner.Workload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes results-db.json and results-db.md, separate from the committed in-memory results. */
final class DbResultsFiles {

    private DbResultsFiles() {}

    static void write(Path dir, Environment env, Workload workload, List<ScenarioResult> results) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("results-db.json"), JsonWriter.write(env, results), StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("results-db.md"),
                    "# Database results\n\nWorkload: `" + workload + "`\n\n" + ResultsTable.markdown(env, results),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
