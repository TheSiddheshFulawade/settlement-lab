package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** An idle event stream must not hold graceful shutdown for the whole phase timeout (REQ-API-006). */
class EventStreamGracefulShutdownTest {

    private static final Duration PHASE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @DisplayName("REQ-API-006 an idle open event stream does not hold graceful shutdown for the phase timeout")
    void idleStreamDoesNotDelayShutdown() throws Exception {
        // Command-line arguments, not builder.properties(): those are default properties, the lowest
        // precedence, so application.yml (20s) would win over the 10s this test means to use.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SettlementApiApplication.class)
                .run(
                        "--server.port=0",
                        "--server.shutdown=graceful",
                        "--spring.lifecycle.timeout-per-shutdown-phase=" + PHASE_TIMEOUT.toSeconds() + "s",
                        "--settlement.workers=1",
                        "--settlement.accounts=5",
                        "--settlement.opening-balance=1000");
        try {
            String port = context.getEnvironment().getProperty("local.server.port");
            EventStreamLineSeparatorIdTest.RawStream stream =
                    EventStreamLineSeparatorIdTest.RawStream.open("http://localhost:" + port + "/api/v1/events");
            await().atMost(Duration.ofSeconds(10)).until(() -> stream.text().contains("connected"));
            post(port, "over-http"); // the stream is live and the engine is working
            // Handed over without waiting, one worker: most are still queued when the context closes, so
            // the drain check below fails if shutdown ever discards accepted transfers.
            SettlementRuntime runtime = context.getBean(SettlementRuntime.class);
            List<CompletionStage<Outcome>> accepted = new ArrayList<>();
            for (int i = 0; i < 900; i++) {
                accepted.add(runtime.engine()
                        .submit(new Transfer(
                                TransferId.of("drain-" + i), AccountId.of(1), AccountId.of(2), Money.of(1))));
            }

            long start = System.nanoTime();
            context.close();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took)
                    .as("context.close() took %s with one idle stream; phase timeout is %s", took, PHASE_TIMEOUT)
                    .isLessThan(PHASE_TIMEOUT.dividedBy(2));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(stream.ended())
                            .as("the client's stream must end when the server shuts down")
                            .isTrue());
            assertThat(accepted)
                    .as("every accepted transfer has its outcome when shutdown completes")
                    .allSatisfy(a -> assertThat(a.toCompletableFuture()).isCompletedWithValue(Outcome.POSTED));
            assertThat(runtime.ledger().balance(AccountId.of(1)))
                    .as("every accepted transfer is drained before shutdown completes")
                    .contains(Money.of(1000 - 901));
        } finally {
            context.close();
        }
    }

    private static void post(String port, String id) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"id\":\"" + id + "\",\"source\":1,\"target\":2,\"amount\":1}"))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isLessThan(300);
    }
}
