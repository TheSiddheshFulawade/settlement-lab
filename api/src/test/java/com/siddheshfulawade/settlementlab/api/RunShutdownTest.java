package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** An application stop during a run ends the run and its open event stream promptly (REQ-API-006). */
class RunShutdownTest {

    private static final Duration PHASE_TIMEOUT = Duration.ofSeconds(10);

    private static long engineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.equals("gl-relay") || name.startsWith("settle-") || name.startsWith("run-"))
                .count();
    }

    @Test
    @DisplayName("REQ-API-006 stopping the application during a run ends its event stream and its threads promptly")
    void shutdownEndsRunAndStream() throws Exception {
        long before = engineThreads();
        // Command-line arguments: they beat application.yml, which the builder's default properties do not.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SettlementApiApplication.class)
                .run(
                        "--server.port=0",
                        "--server.shutdown=graceful",
                        "--spring.lifecycle.timeout-per-shutdown-phase=" + PHASE_TIMEOUT.toSeconds() + "s",
                        "--settlement.runs.sample-interval=50ms");
        try {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            HttpResponse<String> accepted = RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp());
            String runId = RunHttp.runIdOf(accepted);
            EventStreamLineSeparatorIdTest.RawStream stream = EventStreamLineSeparatorIdTest.RawStream.open(
                    "http://localhost:" + port + "/api/v1/runs/" + runId + "/events");
            await().atMost(Duration.ofSeconds(20)).until(() -> stream.text().contains("metrics"));
            assertThat(stream.ended())
                    .as("the run is still going when the application stops")
                    .isFalse();

            long start = System.nanoTime();
            context.close();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took)
                    .as("context.close() took %s with a run streaming; phase timeout is %s", took, PHASE_TIMEOUT)
                    .isLessThan(PHASE_TIMEOUT.dividedBy(2));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(stream.ended())
                            .as("the client's stream must end when the server shuts down")
                            .isTrue());
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(engineThreads())
                            .as("engine, outbox and sampler threads left behind")
                            .isLessThanOrEqualTo(before));
        } finally {
            context.close();
        }
    }
}
