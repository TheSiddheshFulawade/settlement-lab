package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TEMPORARY (V2-07 pacing question, removed before the PR leaves draft): prints measurements, asserts only
 * that the child JVM ended by itself.
 */
class RunPacingMeasurementTest {

    @TempDir
    Path dir;

    @Test
    void allProcessors() throws Exception {
        measure("all processors", List.of());
    }

    @Test
    void oneProcessor() throws Exception {
        measure("JVM limited to one processor, not pinned to one core", List.of("-XX:ActiveProcessorCount=1"));
    }

    private void measure(String label, List<String> jvmOptions) throws Exception {
        Path output = dir.resolve("out.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.addAll(List.of(
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                RunPacingMeasurement.class.getName(),
                label));
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            boolean ended = child.waitFor(360, TimeUnit.SECONDS);
            System.out.println(Files.readString(output));
            assertThat(ended).as("the measurement ended by itself").isTrue();
            assertThat(child.exitValue()).isZero();
        } finally {
            child.destroyForcibly().waitFor();
        }
    }
}
