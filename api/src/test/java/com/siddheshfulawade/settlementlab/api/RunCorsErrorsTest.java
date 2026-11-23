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
 * REQ-RUN-008: every error body (400, 404, 429, 503; 413 is in InputLimitsHttpTest) carries the CORS headers
 * for an allowed origin, so the page can read the problem and Retry-After; a foreign origin gets none. The clock
 * is frozen (FrozenClockConfig) so that Retry-After values are exact.
 */
@SpringBootTest(
        properties = {
            "settlement.runs.preview-origin=https://develop.portfolio-7qt.pages.dev",
            "settlement.runs.max-active=1",
            "settlement.runs.per-ip-per-hour=1",
            "settlement.runs.global-per-hour=1000",
            "settlement.runs.transfers-per-second=200",
            "settlement.runs.deadline=2s"
        })
@AutoConfigureMockMvc
@Import(FrozenClockConfig.class)
class RunCorsErrorsTest {

    private static final String ORIGIN = "https://siddheshfulawade.com";
    private static final String PREVIEW = "https://develop.portfolio-7qt.pages.dev";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void emptyWindows() {
        clock.advance(Duration.ofHours(2));
    }

    private MockHttpServletResponse post(String body, String ip, String origin) {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .header("Origin", origin)
                .content(body)
                .exchange()
                .getResponse();
    }

    private static void assertCorsFor(MockHttpServletResponse response, String origin) {
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(CorsProbe.values(response, "Access-Control-Expose-Headers")).contains("location", "retry-after");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    @DisplayName("REQ-RUN-008 a 400 problem has the CORS headers")
    void badRequestHasCors() {
        for (String origin : new String[] {ORIGIN, PREVIEW}) {
            MockHttpServletResponse response = post("{\"ledger\":\"NOPE\"}", RunHttp.nextIp(), origin);

            assertThat(response.getStatus()).isEqualTo(400);
            assertCorsFor(response, origin);
        }
    }

    @Test
    @DisplayName("REQ-RUN-008 a 404 problem has the CORS headers")
    void notFoundHasCors() {
        MockHttpServletResponse response = new CorsProbe(mvc).getUnknownRun(ORIGIN);

        assertThat(response.getStatus()).isEqualTo(404);
        assertCorsFor(response, ORIGIN);
    }

    @Test
    @DisplayName("REQ-RUN-008 a 429 problem has the CORS headers, and none for a foreign origin")
    void rateLimitedHasCors() throws Exception {
        String repeat = RunHttp.nextIp();
        // a 503 (slot busy with an earlier quick run) uses nothing, so retry until the first start is accepted
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> assertThat(post(RunHttp.QUICK_RUN, repeat, ORIGIN).getStatus())
                                .isEqualTo(202));
        // the per-IP check comes before the active-run check, so the second start is 429 whether or not a run is active
        MockHttpServletResponse refused = post(RunHttp.QUICK_RUN, repeat, ORIGIN);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertCorsFor(refused, ORIGIN);
        assertThat(Long.parseLong(refused.getHeader("Retry-After")))
                .isEqualTo(((Integer) JsonPath.read(refused.getContentAsString(), "$.retryAfterSeconds")).longValue());
        assertThat(CorsProbe.corsHeaders(post(RunHttp.QUICK_RUN, repeat, "https://evil.example")))
                .isEmpty();
    }

    @Test
    @DisplayName("REQ-RUN-008 a 503 problem has the CORS headers")
    void busyHasCors() {
        String holder = RunHttp.nextIp();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> assertThat(post(RunHttp.SLOW_RUN, holder, ORIGIN).getStatus())
                                .isIn(202, 429));

        MockHttpServletResponse refused = post(RunHttp.QUICK_RUN, RunHttp.nextIp(), PREVIEW);

        assertThat(refused.getStatus()).isEqualTo(503);
        assertCorsFor(refused, PREVIEW);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
        assertThat(CorsProbe.corsHeaders(post(RunHttp.QUICK_RUN, RunHttp.nextIp(), "https://evil.example")))
                .isEmpty();
        // the clock is frozen: pass the 2 s deadline so the held run ends and the next test finds a free slot
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> post(RunHttp.QUICK_RUN, RunHttp.nextIp(), ORIGIN).getStatus() == 202);
    }
}
