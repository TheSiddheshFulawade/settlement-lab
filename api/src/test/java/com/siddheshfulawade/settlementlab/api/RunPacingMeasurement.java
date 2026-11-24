package com.siddheshfulawade.settlementlab.api;

import ch.qos.logback.classic.Level;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.LoggerFactory;

/**
 * TEMPORARY (V2-07 pacing question, removed before the PR leaves draft). Runs full-size runs in this JVM
 * through RunService, with no HTTP server, prints one line per run and exits; a watchdog halts the JVM if
 * it hangs.
 */
final class RunPacingMeasurement {

    private static final Duration WATCHDOG = Duration.ofSeconds(330);

    private RunPacingMeasurement() {}

    public static void main(String[] args) {
        Thread.ofPlatform().daemon().start(() -> {
            long deadline = System.nanoTime() + WATCHDOG.toNanos();
            for (long left = WATCHDOG.toNanos(); left > 0; left = deadline - System.nanoTime()) {
                LockSupport.parkNanos(left);
            }
            System.out.println("PACING watchdog: halted");
            Runtime.getRuntime().halt(2);
        });
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ROOT")).setLevel(Level.WARN);
        String label = args[0];
        System.out.printf(
                "PACING %s | os=%s java=%s cpus=%d%n",
                label,
                System.getProperty("os.name"),
                Runtime.version().feature(),
                Runtime.getRuntime().availableProcessors());
        probe(label, "platform", Thread.ofPlatform());
        probe(label, "virtual", Thread.ofVirtual());
        probeSixteen(label + " | before");

        RunRequest lockingVirtual = request(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.FLAKY);
        RunRequest casPlatform = request(LedgerChoice.CAS, ThreadMode.PLATFORM, FaultChoice.FLAKY);
        runTogether(label + " | flaky x1", false, lockingVirtual);
        runTogether(label + " | flaky-no-latency x1", true, lockingVirtual);
        runTogether(label + " | flaky x2", false, lockingVirtual, casPlatform);
        runTogether(
                label + " | outage x2",
                false,
                request(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.OUTAGE),
                request(LedgerChoice.CAS, ThreadMode.PLATFORM, FaultChoice.OUTAGE));
        probeSixteen(label + " | after");
        System.exit(0);
    }

    private static RunRequest request(LedgerChoice ledger, ThreadMode threads, FaultChoice faults) {
        return new RunRequest(ledger, threads, faults, RunRequest.MAX_TRANSFERS, 2);
    }

    /** What a 1 ms wait and a 50 ms sleep (the GL latency and the first retry wait) really take here. */
    private static void probe(String label, String kind, Thread.Builder builder) {
        double[] average = new double[2];
        Thread thread = builder.start(() -> {
            average[0] = averageMillis(200, () -> LockSupport.parkNanos(1_000_000));
            average[1] = averageMillis(20, () -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.printf(
                "PACING %s | probe %s parkNanos(1ms)=%.2fms sleep(50ms)=%.2fms%n", label, kind, average[0], average[1]);
    }

    /** As many waiters at once as the outbox has senders, each parking 1 ms in a loop. */
    private static void probeSixteen(String label) {
        double[] averages = new double[16];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < averages.length; i++) {
            int index = i;
            threads.add(Thread.ofVirtual()
                    .start(() -> averages[index] = averageMillis(200, () -> LockSupport.parkNanos(1_000_000))));
        }
        double sum = 0;
        for (int i = 0; i < averages.length; i++) {
            try {
                threads.get(i).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sum += averages[i];
        }
        System.out.printf("PACING %s | probe 16 virtual parkNanos(1ms)=%.2fms%n", label, sum / averages.length);
    }

    private static double averageMillis(int times, Runnable wait) {
        long start = System.nanoTime();
        for (int i = 0; i < times; i++) {
            wait.run();
        }
        return (System.nanoTime() - start) / 1e6 / times;
    }

    private static void runTogether(String label, boolean noLatency, RunRequest... requests) {
        RunsProperties properties =
                RunsProperties.defaults().withPerIpPerHour(100).withGlobalPerHour(100);
        RunService.RuntimeFactory factory = (settings, listener) -> {
            FaultProfile profile = properties.faultProfile(settings.faults());
            return SettlementRuntime.start(
                    settings, listener, noLatency ? profile.withLatency(Duration.ZERO) : profile);
        };
        try (RunService service = new RunService(properties, Clock.systemUTC(), factory)) {
            List<Run> runs = new ArrayList<>();
            for (RunRequest request : requests) {
                runs.add(service.start(request, "measurement"));
            }
            for (Run run : runs) {
                while (run.isRunning()) {
                    LockSupport.parkNanos(50_000_000);
                }
                System.out.println("PACING " + label + " | " + run.settings().ledger() + "/"
                        + run.settings().threads() + " | "
                        + run.report().map(RunPacingMeasurement::line).orElse("NO REPORT"));
            }
        }
    }

    private static String line(RunReport report) {
        return "durationMs=" + report.durationMs() + " early=" + report.stoppedEarly() + " done="
                + report.transfersCompleted() + " tps=" + report.throughputPerSecond() + " p50=" + report.p50Micros()
                + " p99=" + report.p99Micros() + " retries=" + report.glRetries() + " opens=" + report.breakerOpens()
                + " pass=" + report.allPassed();
    }
}
