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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs that are still going while the test looks: pacing is 200 transfers/s and the deadline 2 s, so a
 * 20,000-transfer run is active for 2 s, is then stopped early, and reports a few seconds later.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.runs.transfers-per-second=200",
            "settlement.runs.deadline=2s",
            "settlement.runs.outage-start=100ms",
            "settlement.runs.outage-length=400ms",
            "settlement.runs.max-active=10",
            "settlement.runs.per-ip-per-hour=1000",
            "settlement.runs.global-per-hour=1000"
        })
class RunHeldHttpTest {

    @LocalServerPort
    int port;

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
    @DisplayName("REQ-RUN-001 GET status is {runId,status} while RUNNING, then the full report once DONE")
    void statusThenReport() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));

        HttpResponse<String> running = RunHttp.get(port, "/api/v1/runs/" + runId);

        assertThat(running.statusCode()).isEqualTo(200);
        assertThat(RunHttp.json(running.body())).isEqualTo(Map.of("runId", runId, "status", "RUNNING"));
        Map<String, Object> report = awaitReport(runId);
        RunHttp.assertContractReport(report, runId);
        assertThat(report.get("stoppedEarly")).isEqualTo(true);
        assertThat(RunHttp.number(report, "transfersCompleted")).isBetween(1L, 19_999L);
        assertThat(RunHttp.number(report, "durationMs")).isGreaterThanOrEqualTo(2_000);
        assertThat(RunHttp.number(report, "throughputPerSecond"))
                .as("the paced rate, not capacity")
                .isLessThanOrEqualTo(240);
    }

    @Test
    @DisplayName("REQ-RUN-003 the stream sends id-less metrics with the contract fields, one report, then closes")
    void streamsMetricsThenReportThenCloses() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));
        EventStreamLineSeparatorIdTest.RawStream stream = EventStreamLineSeparatorIdTest.RawStream.open(
                "http://localhost:" + port + "/api/v1/runs/" + runId + "/events");

        await().atMost(Duration.ofSeconds(90)).until(stream::ended);

        List<SseText.Event> events = SseText.parse(stream.text());
        assertThat(stream.text()).as("no id: field anywhere").doesNotContainPattern("(?m)^id:");
        assertThat(events).extracting(SseText.Event::name).containsOnly("metrics", "report");
        assertThat(events.stream().filter(e -> e.name().equals("report")).count())
                .isEqualTo(1);
        assertThat(events.get(events.size() - 1).name())
                .as("the report is last")
                .isEqualTo("report");
        List<SseText.Event> metrics =
                events.stream().filter(e -> e.name().equals("metrics")).toList();
        assertThat(metrics.size()).as("a run of over 2 s sends several metrics").isGreaterThanOrEqualTo(3);
        long lastElapsed = -1;
        for (SseText.Event metric : metrics) {
            assertThat(metric.dataLines()).as("the server sends one data line").hasSize(1);
            Map<String, Object> data = RunHttp.json(metric.data());
            assertThat(data.keySet()).isEqualTo(RunHttp.METRICS_KEYS);
            assertThat(data.get("breakerState")).isIn("CLOSED", "OPEN", "HALF_OPEN");
            assertThat(RunHttp.number(data, "queueCapacity")).isEqualTo(1_024);
            assertThat(RunHttp.number(data, "elapsedMs")).isGreaterThanOrEqualTo(lastElapsed);
            assertThat(RunHttp.number(data, "posted")).isLessThanOrEqualTo(RunHttp.number(data, "submitted"));
            lastElapsed = RunHttp.number(data, "elapsedMs");
        }
        Map<String, Object> reported =
                RunHttp.json(events.get(events.size() - 1).data());
        RunHttp.assertContractReport(reported, runId);
        assertThat(reported)
                .as("the report event and GET /runs/{id} carry the same body")
                .isEqualTo(
                        RunHttp.json(RunHttp.get(port, "/api/v1/runs/" + runId).body()));
    }

    @Test
    @DisplayName("REQ-RUN-003 a Last-Event-ID on a live stream replays nothing: it behaves like a new connection")
    void lastEventIdOnLiveStream() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));

        try (LiveStream stream =
                LiveStream.open(port, "/api/v1/runs/" + runId + "/events", Map.of("Last-Event-ID", "12"))) {
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> stream.text().contains("event:metrics")
                            || stream.text().contains("event: metrics"));

            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.text()).doesNotContainPattern("(?m)^id:");
        }
        awaitReport(runId);
    }

    @Test
    @DisplayName(
            "REQ-RUN-011 a third subscriber is 429 rate-limited with Retry-After 5 matching its body; a closed one frees its place")
    void thirdSubscriberIsRefused() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));
        String events = "/api/v1/runs/" + runId + "/events";
        EventStreamLineSeparatorIdTest.RawStream first =
                EventStreamLineSeparatorIdTest.RawStream.open("http://localhost:" + port + events);
        EventStreamLineSeparatorIdTest.RawStream second =
                EventStreamLineSeparatorIdTest.RawStream.open("http://localhost:" + port + events);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> first.text().contains("metrics") && second.text().contains("metrics"));

        HttpResponse<String> third = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + events))
                                .header("Accept", "text/event-stream")
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(third.body(), "$.type")).isEqualTo(RunHttp.PROBLEM_BASE + "rate-limited");
        assertThat(third.headers().firstValue("Retry-After")).contains("5");
        assertThat((Integer) JsonPath.read(third.body(), "$.retryAfterSeconds")).isEqualTo(5);

        first.close();
        // The server notices on its next failed write; 5 s is the promised 1 s with room for HTTP and a slow CI.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    EventStreamLineSeparatorIdTest.RawStream attempt =
                            EventStreamLineSeparatorIdTest.RawStream.open("http://localhost:" + port + events);
                    await().atMost(Duration.ofSeconds(3))
                            .until(() -> attempt.ended() || attempt.text().contains("event:"));
                    return attempt.text().contains("event:");
                });
        second.close();
        awaitReport(runId);
    }

    @Test
    @DisplayName("REQ-RUN-008 the live event stream carries the CORS headers for an allowed origin")
    void liveStreamHasCorsHeaders() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));

        try (LiveStream allowed = LiveStream.open(
                        port, "/api/v1/runs/" + runId + "/events", Map.of("Origin", "https://siddheshfulawade.com"));
                LiveStream foreign = LiveStream.open(
                        port, "/api/v1/runs/" + runId + "/events", Map.of("Origin", "https://evil.example"))) {
            assertThat(allowed.awaitHeaders(Duration.ofSeconds(20))).isTrue();
            assertThat(foreign.awaitHeaders(Duration.ofSeconds(20))).isTrue();

            assertThat(allowed.status()).isEqualTo(200);
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains("https://siddheshfulawade.com");
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials"))
                    .isEmpty();
            assertThat(foreign.headers().map().keySet())
                    .noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
        }
        awaitReport(runId);
    }
}
