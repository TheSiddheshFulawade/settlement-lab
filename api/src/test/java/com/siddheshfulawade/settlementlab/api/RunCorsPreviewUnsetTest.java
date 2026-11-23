package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** REQ-RUN-008 with {@code settlement.runs.preview-origin} unset: only the production origin is allowed. */
@SpringBootTest(
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.outage-start=100ms",
            "settlement.runs.outage-length=400ms",
            "settlement.runs.max-active=20",
            "settlement.runs.per-ip-per-hour=1000",
            "settlement.runs.global-per-hour=1000"
        })
@AutoConfigureMockMvc
class RunCorsPreviewUnsetTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("REQ-RUN-008 the production origin still works when no preview origin is set")
    void productionOriginWorks() {
        CorsProbe probe = new CorsProbe(mvc);

        assertThat(probe.preflight("https://siddheshfulawade.com").getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://siddheshfulawade.com");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "https://develop.portfolio-7qt.pages.dev",
                "https://pages.dev",
                "https://anything.pages.dev",
                "",
                " ",
                "null",
                "*"
            })
    @DisplayName("REQ-RUN-008 an unset preview origin allows nobody: no empty or wildcard match")
    void unsetPreviewOriginAllowsNobody(String origin) {
        CorsProbe probe = new CorsProbe(mvc);

        assertThat(CorsProbe.corsHeaders(probe.preflight(origin)))
                .as("preflight")
                .isEmpty();
        assertThat(CorsProbe.corsHeaders(probe.start(origin))).as("POST").isEmpty();
    }
}
