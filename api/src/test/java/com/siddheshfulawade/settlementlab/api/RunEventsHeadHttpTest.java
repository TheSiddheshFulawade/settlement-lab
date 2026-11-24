package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * REQ-RUN-011: only a real viewer takes one of a run's two subscriber places. The properties are those of
 * RunHeldHttpTest on purpose, so both share one cached application context; each test starts its own run
 * from its own address.
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
class RunEventsHeadHttpTest {

    @LocalServerPort
    int port;

    /** The status of a HEAD, or -1 if there was no answer in 5 s. */
    private int head(String path) {
        try {
            return HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                    .timeout(Duration.ofSeconds(5))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception noAnswer) {
            return -1;
        }
    }

    @Test
    @DisplayName(
            "REQ-RUN-011 HEAD on a run's events is 405 and takes no subscriber place: two real viewers still get in")
    void headDoesNotTakeASubscriberPlace() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.SLOW_RUN, RunHttp.nextIp()));
        String events = "/api/v1/runs/" + runId + "/events";

        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            heads.add(head(events));
        }
        try (LiveStream first = LiveStream.open(port, events, Map.of());
                LiveStream second = LiveStream.open(port, events, Map.of())) {
            boolean firstAnswered = first.awaitHeaders(Duration.ofSeconds(20));
            boolean secondAnswered = second.awaitHeaders(Duration.ofSeconds(20));

            assertSoftly(softly -> {
                softly.assertThat(heads).as("HEAD statuses (-1: no answer)").containsExactly(405, 405, 405);
                softly.assertThat(firstAnswered)
                        .as("first viewer got an answer")
                        .isTrue();
                softly.assertThat(secondAnswered)
                        .as("second viewer got an answer")
                        .isTrue();
                softly.assertThat(first.status()).as("first viewer").isEqualTo(200);
                softly.assertThat(second.status()).as("second viewer").isEqualTo(200);
            });
        }
    }

    @Test
    @DisplayName("REQ-RUN-011 HEAD on a run's events still answers 404 for an unknown run, never 200")
    void headOnUnknownRunIsNotFoundOrNotAllowed() {
        assertThat(head("/api/v1/runs/r-0000000000000000/events")).isIn(404, 405);
    }
}
