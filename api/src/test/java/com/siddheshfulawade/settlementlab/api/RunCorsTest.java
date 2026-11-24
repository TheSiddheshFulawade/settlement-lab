package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * REQ-RUN-008 with a preview origin configured: exactly the production origin and the preview origin get
 * CORS headers (GET and POST, Content-Type, exposed Location and Retry-After, no credentials), nobody else.
 */
@SpringBootTest(
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.outage-start=100ms",
            "settlement.runs.outage-length=400ms",
            "settlement.runs.preview-origin=https://develop.portfolio-7qt.pages.dev",
            "settlement.runs.max-active=20",
            "settlement.runs.per-ip-per-hour=1000",
            "settlement.runs.global-per-hour=1000"
        })
@AutoConfigureMockMvc
class RunCorsTest {

    @Autowired
    MockMvcTester mvc;

    private CorsProbe probe() {
        return new CorsProbe(mvc);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"https://siddheshfulawade.com", "https://develop.portfolio-7qt.pages.dev"})
    @DisplayName(
            "REQ-RUN-008 an allowed origin's preflight for POST with Content-Type is approved, without credentials")
    void preflightIsApproved(String origin) {
        MockHttpServletResponse response = probe().preflight(origin);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(CorsProbe.values(response, "Access-Control-Allow-Methods")).contains("post");
        assertThat(CorsProbe.values(response, "Access-Control-Allow-Headers")).contains("content-type");
        assertThat(response.getHeader("Access-Control-Allow-Credentials"))
                .as("no credentials")
                .isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"https://siddheshfulawade.com", "https://develop.portfolio-7qt.pages.dev"})
    @DisplayName(
            "REQ-RUN-008 a real request from an allowed origin gets the origin back and exposes Location and Retry-After")
    void actualRequestExposesHeaders(String origin) {
        MockHttpServletResponse response = probe().start(origin);

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(CorsProbe.values(response, "Access-Control-Expose-Headers")).contains("location", "retry-after");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"https://siddheshfulawade.com", "https://develop.portfolio-7qt.pages.dev"})
    @DisplayName("REQ-RUN-008 /actuator/health carries the CORS headers for an allowed origin")
    void healthHasCorsHeaders(String origin) {
        MockHttpServletResponse response = probe().health(origin);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"https://evil.example", "https://siddheshfulawade.com.evil.example", "null"})
    @DisplayName("REQ-RUN-008 /actuator/health gives a foreign origin no CORS header")
    void healthHasNoCorsHeadersForForeignOrigins(String origin) {
        assertThat(CorsProbe.corsHeaders(probe().health(origin))).isEmpty();
    }

    @Test
    @DisplayName("REQ-RUN-008 error answers (404) carry the CORS headers too, so the page can read them")
    void errorAnswersAreReadableByThePage() {
        MockHttpServletResponse status = probe().getUnknownRun("https://siddheshfulawade.com");
        MockHttpServletResponse events = probe().getUnknownRunEvents("https://siddheshfulawade.com");

        assertThat(status.getStatus()).isEqualTo(404);
        assertThat(status.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://siddheshfulawade.com");
        assertThat(events.getStatus()).isEqualTo(404);
        assertThat(events.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://siddheshfulawade.com");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "https://evil.example",
                "https://siddheshfulawade.com.evil.example",
                "https://evilsiddheshfulawade.com",
                "http://siddheshfulawade.com",
                "https://www.siddheshfulawade.com",
                "https://siddheshfulawade.com:8443",
                "https://siddheshfulawade.com/",
                "https://develop.portfolio-7qt.pages.dev.evil.example",
                "http://develop.portfolio-7qt.pages.dev",
                "https://other-preview.pages.dev",
                "null",
                "*"
            })
    @DisplayName("REQ-RUN-008 a foreign or look-alike origin gets no CORS header, on a preflight or a real request")
    void foreignOriginsGetNothing(String origin) {
        assertThat(CorsProbe.corsHeaders(probe().preflight(origin)))
                .as("preflight")
                .isEmpty();
        assertThat(CorsProbe.corsHeaders(probe().start(origin))).as("POST").isEmpty();
        assertThat(CorsProbe.corsHeaders(probe().getUnknownRun(origin)))
                .as("GET")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "REQ-RUN-008 a request without an Origin header (curl, server to server) works and gets no CORS header")
    void noOriginIsPlainRequest() {
        MockHttpServletResponse response = probe().startWithoutOrigin();

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(CorsProbe.corsHeaders(response)).isEmpty();
    }
}
