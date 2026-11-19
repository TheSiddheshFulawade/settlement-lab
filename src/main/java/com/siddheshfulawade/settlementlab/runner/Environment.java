package com.siddheshfulawade.settlementlab.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The machine a run happened on, printed with every table: numbers mean nothing without it. */
public record Environment(String jdk, String cpu, int cores, String os) {

    public static Environment detect() {
        return new Environment(
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                cpuModel(),
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    }

    private static String cpuModel() {
        String windows = System.getenv("PROCESSOR_IDENTIFIER");
        if (windows != null) {
            return windows;
        }
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unknown CPU");
        } catch (IOException | RuntimeException e) {
            return "unknown CPU";
        }
    }
}
