package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The runs API over real HTTP, fast pacing, checked against docs/api-contract.md. Runs here finish by
 * themselves within a few seconds; the tests about a run that is still going are in RunHeldHttpTest.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.outage-start=100ms",
            "settlement.runs.outage-length=400ms",
            "settlement.runs.max-active=10",
            "settlement.runs.per-ip-per-hour=1000",
            "settlement.runs.global-per-hour=1000"
        })
@Import(FakeClockConfig.class)
class RunContractHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    private HttpResponse<String> start(String body) throws Exception {
        return RunHttp.post(port, body, RunHttp.nextIp());
    }

    private Map<String, Object> awaitReport(String runId) {
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    HttpResponse<String> status = RunHttp.get(port, "/api/v1/runs/" + runId);
                    return status.statusCode() == 200 && "DONE".equals(JsonPath.read(status.body(), "$.status"));
                });
        try {
            return RunHttp.json(RunHttp.get(port, "/api/v1/runs/" + runId).body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("REQ-RUN-001 a valid request is 202 with Location and a body exactly as the contract shows")
    void acceptedBodyMatchesContract() throws Exception {
        HttpResponse<String> response = start(
                "{\"ledger\":\"LOCKING\",\"threads\":\"VIRTUAL\",\"faults\":\"NONE\",\"transfers\":200,\"duplicatePercent\":2}");

        String runId = RunHttp.runIdOf(response);

        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(RunHttp.json(response.body()))
                .isEqualTo(Map.of(
                        "runId",
                        runId,
                        "status",
                        "RUNNING",
                        "eventsUrl",
                        "/api/v1/runs/" + runId + "/events",
                        "limits",
                        Map.of("maxSeconds", 20, "runsPerHourPerIp", 5, "concurrentRuns", 2)));
        awaitReport(runId);
    }

    @Test
    @DisplayName("REQ-RUN-010 GET of a finished run is the contract's report with plain integer totals, nothing else")
    void finishedRunBodyMatchesContract() throws Exception {
        String runId = RunHttp.runIdOf(start(RunHttp.FLAKY_RUN));

        Map<String, Object> report = awaitReport(runId);

        RunHttp.assertContractReport(report, runId);
        assertThat(report.get("stoppedEarly")).isEqualTo(false);
        assertThat(RunHttp.number(report, "transfersCompleted")).isEqualTo(3_000);
        assertThat(RunHttp.number(report, "duplicatesSent")).isPositive();
        assertThat(RunHttp.number(report, "glRetries")).as("FLAKY retries").isPositive();
        assertThat(RunHttp.number(report, "throughputPerSecond")).isPositive();
    }

    @Test
    @DisplayName("REQ-RUN-004 connecting after the run finished gives only the report, with no id field, and closes")
    void lateConnectionGetsReport() throws Exception {
        String runId = RunHttp.runIdOf(start(RunHttp.QUICK_RUN));
        awaitReport(runId);

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/v1/runs/" + runId + "/events"))
                                .header("Accept", "text/event-stream")
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<SseText.Event> events = SseText.parse(response.body());
        assertThat(events).extracting(SseText.Event::name).containsExactly("report");
        assertThat(events.get(0).id()).isNull();
        assertThat(response.body()).as("no id: field anywhere").doesNotContainPattern("(?m)^id:");
        RunHttp.assertContractReport(RunHttp.json(events.get(0).data()), runId);
    }

    @Test
    @DisplayName("REQ-RUN-003 a Last-Event-ID header changes nothing: the request behaves like a new one")
    void lastEventIdIsIgnored() throws Exception {
        String runId = RunHttp.runIdOf(start(RunHttp.QUICK_RUN));
        awaitReport(runId);

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/v1/runs/" + runId + "/events"))
                                .header("Accept", "text/event-stream")
                                .header("Last-Event-ID", "12")
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(SseText.parse(response.body()))
                .extracting(SseText.Event::name)
                .containsExactly("report");
    }

    @Test
    @DisplayName("REQ-RUN-003 an unknown run, or an id that is not a valid run id, is 404 run-not-found")
    void unknownRunIs404() throws Exception {
        List<String> ids =
                List.of("r-00000000", "nope", "x".repeat(65), "r-1.2", "r%20x", "%C3%A9", "r_1", "..%2F..%2Factuator");
        for (String id : ids) {
            for (String path : List.of("/api/v1/runs/" + id, "/api/v1/runs/" + id + "/events")) {
                HttpResponse<String> response = RunHttp.get(port, path);

                assertThat(response.statusCode()).as(path).isEqualTo(404);
                assertThat(response.headers().firstValue("Content-Type").orElse(""))
                        .as(path)
                        .startsWith("application/problem+json");
                assertThat((String) JsonPath.read(response.body(), "$.type"))
                        .as(path)
                        .isEqualTo(RunHttp.PROBLEM_BASE + "run-not-found");
                assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("REQ-RUN-012 a finished run answers 404 run-not-found once its 10 minutes have passed")
    void expiredRunIs404() throws Exception {
        String runId = RunHttp.runIdOf(start(RunHttp.QUICK_RUN));
        awaitReport(runId);
        assertThat(RunHttp.get(port, "/api/v1/runs/" + runId).statusCode()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        for (String path : List.of("/api/v1/runs/" + runId, "/api/v1/runs/" + runId + "/events")) {
            HttpResponse<String> response = RunHttp.get(port, path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat((String) JsonPath.read(response.body(), "$.type"))
                    .isEqualTo(RunHttp.PROBLEM_BASE + "run-not-found");
        }
    }
}
