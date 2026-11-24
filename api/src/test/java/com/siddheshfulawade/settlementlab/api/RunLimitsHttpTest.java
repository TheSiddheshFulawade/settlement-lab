package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The limits mapped to HTTP (REQ-RUN-006, 007) with a hand-moved clock: 2 runs per IP per hour, 5 per
 * hour overall, 1 active run. The numbers are smaller than the defaults to keep the tests short; the
 * defaults themselves are pinned in RunServiceTest. The clock is frozen (FrozenClockConfig), so the exact
 * Retry-After values below do not drift with real time.
 */
@SpringBootTest(
        properties = {
            "settlement.runs.transfers-per-second=200",
            "settlement.runs.deadline=2s",
            "settlement.runs.max-active=1",
            "settlement.runs.per-ip-per-hour=2",
            "settlement.runs.global-per-hour=5"
        })
@AutoConfigureMockMvc
@Import(FrozenClockConfig.class)
class RunLimitsHttpTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void emptyWindows() {
        clock.advance(Duration.ofHours(2));
    }

    private MockHttpServletResponse post(String ip) {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    /** Starts a quick run, waiting out a 503 caused only by the previous quick run still winding down. */
    private void startAccepted(String ip) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    MockHttpServletResponse response = post(ip);
                    assertThat(response.getStatus())
                            .as(response.getContentAsString())
                            .isEqualTo(202);
                });
    }

    /**
     * Repeats a request until it gets {@code status}. A refusal consumes nothing, so repeating is safe; it is
     * needed because a previous quick run may still hold the only active slot for a moment, and which
     * refusal comes first when several apply is not part of the contract.
     */
    private static MockHttpServletResponse until(
            java.util.function.Supplier<MockHttpServletResponse> request, int status) {
        java.util.concurrent.atomic.AtomicReference<MockHttpServletResponse> last =
                new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> {
                    last.set(request.get());
                    assertThat(last.get().getStatus()).isEqualTo(status);
                });
        return last.get();
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String suffix) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(body, "$.type")).isEqualTo(RunHttp.PROBLEM_BASE + suffix);
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(status);
    }

    @Test
    @DisplayName(
            "REQ-RUN-006 the third run from one IP in an hour is 429 with the exact Retry-After and a matching body")
    void perIpLimitIs429() throws Exception {
        String ip = RunHttp.nextIp();
        startAccepted(ip); // t = 0
        clock.advance(Duration.ofMinutes(10));
        startAccepted(ip); // t = 10 min
        clock.advance(Duration.ofMinutes(5)); // t = 15 min: the oldest leaves in 45 minutes

        MockHttpServletResponse refused = until(() -> post(ip), 429);

        assertProblem(refused, 429, "rate-limited");
        assertThat(refused.getHeader("Retry-After")).isEqualTo("2700");
        String body = refused.getContentAsString();
        assertThat((Integer) JsonPath.read(body, "$.retryAfterSeconds")).isEqualTo(2700);
        assertThat((String) JsonPath.read(body, "$.title")).isEqualTo("Too many runs");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("Try again in 2,700 s");
    }

    @Test
    @DisplayName("REQ-RUN-006 a forged first X-Forwarded-For entry does not dodge the limit; the last entry is the key")
    void forgedFirstEntryDoesNotHelp() throws Exception {
        String real = RunHttp.nextIp();
        startAccepted("1.1.1.1, " + real);
        startAccepted("2.2.2.2, " + real);

        assertProblem(until(() -> post("3.3.3.3, " + real), 429), 429, "rate-limited");
        startAccepted("1.1.1.1, " + RunHttp.nextIp()); // a different last entry is a different visitor
    }

    @Test
    @DisplayName("REQ-RUN-006 with two X-Forwarded-For header lines the last entry of the last line is the key")
    void twoHeaderLines() throws Exception {
        String real = RunHttp.nextIp();
        for (int i = 0; i < 2; i++) {
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> assertThat(postTwoLines("9.9.9.9", real).getStatus())
                                    .isEqualTo(202));
        }

        until(() -> postTwoLines("8.8.8.8", real), 429);
    }

    private MockHttpServletResponse postTwoLines(String first, String last) {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", first)
                .header("X-Forwarded-For", last)
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    @Test
    @DisplayName("REQ-RUN-006 without X-Forwarded-For the remote address is the key")
    void remoteAddressIsTheFallback() throws Exception {
        for (int i = 0; i < 2; i++) {
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() ->
                            assertThat(postFromRemote("192.0.2.99").getStatus()).isEqualTo(202));
        }

        until(() -> postFromRemote("192.0.2.99"), 429);
        until(() -> postFromRemote("192.0.2.98"), 202); // another address is another visitor
    }

    private MockHttpServletResponse postFromRemote(String remote) {
        return mvc.post()
                .uri("/api/v1/runs")
                .with(request -> {
                    request.setRemoteAddr(remote);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    @Test
    @DisplayName("REQ-RUN-007 while a run is active, another start is 503 busy with Retry-After 5")
    void busyIs503() throws Exception {
        String slow = RunHttp.nextIp();
        // until: the previous test's quick run may still be closing its engine, and it holds the only slot
        MockHttpServletResponse accepted = until(
                () -> mvc.post()
                        .uri("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", slow)
                        .content(RunHttp.SLOW_RUN)
                        .exchange()
                        .getResponse(),
                202);
        String runId = JsonPath.read(accepted.getContentAsString(), "$.runId");

        MockHttpServletResponse refused = post(RunHttp.nextIp());

        assertProblem(refused, 503, "busy");
        assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
        assertThat((Integer) JsonPath.read(refused.getContentAsString(), "$.retryAfterSeconds"))
                .isEqualTo(5);
        clock.advance(Duration.ofSeconds(3)); // the clock is frozen: pass the 2 s deadline so the run ends
        await().atMost(Duration.ofSeconds(90)).until(() -> {
            MockHttpServletResponse status =
                    mvc.get().uri("/api/v1/runs/" + runId).exchange().getResponse();
            return status.getContentAsString().contains("\"DONE\"");
        });
    }

    @Test
    @DisplayName(
            "REQ-RUN-007 the sixth run in an hour across all visitors is 503 busy with Retry-After until the next slot")
    void globalCapIs503() throws Exception {
        for (int i = 0; i < 5; i++) {
            startAccepted(RunHttp.nextIp()); // starts at 0, 1, 2, 3 and 4 minutes
            clock.advance(Duration.ofMinutes(1));
        }

        String visitor = RunHttp.nextIp();
        // t = 5 min: the oldest start leaves at 60. If a quick run is still active the answer is BUSY with 5
        // instead, so repeat until the global answer shows.
        java.util.concurrent.atomic.AtomicReference<MockHttpServletResponse> last =
                new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            last.set(post(visitor));
            return "3300".equals(last.get().getHeader("Retry-After"));
        });
        MockHttpServletResponse refused = last.get();

        assertProblem(refused, 503, "busy");
        assertThat((Integer) JsonPath.read(refused.getContentAsString(), "$.retryAfterSeconds"))
                .isEqualTo(3300);
    }

    @Test
    @DisplayName("REQ-RUN-007 a refused start has no Location header and starts nothing")
    void refusedStartHasNoLocation() throws Exception {
        String ip = RunHttp.nextIp();
        startAccepted(ip);
        startAccepted(ip);

        MockHttpServletResponse refused = until(() -> post(ip), 429);

        assertThat(refused.getHeader("Location")).isNull();
    }
}
