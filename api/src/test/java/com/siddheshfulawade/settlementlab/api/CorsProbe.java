package com.siddheshfulawade.settlementlab.api;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Sends preflight and real cross-origin requests to the runs API and lists the CORS headers back. */
final class CorsProbe {

    private final MockMvcTester mvc;

    CorsProbe(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    MockHttpServletResponse preflight(String origin) {
        return mvc.options()
                .uri("/api/v1/runs")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .exchange()
                .getResponse();
    }

    MockHttpServletResponse start(String origin) {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", origin)
                .header("X-Forwarded-For", RunHttp.nextIp())
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    MockHttpServletResponse startWithoutOrigin() {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", RunHttp.nextIp())
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    MockHttpServletResponse health(String origin) {
        return mvc.get()
                .uri("/actuator/health")
                .header("Origin", origin)
                .exchange()
                .getResponse();
    }

    MockHttpServletResponse getUnknownRun(String origin) {
        return mvc.get()
                .uri("/api/v1/runs/r-00000000")
                .header("Origin", origin)
                .exchange()
                .getResponse();
    }

    MockHttpServletResponse getUnknownRunEvents(String origin) {
        return mvc.get()
                .uri("/api/v1/runs/r-00000000/events")
                .header("Origin", origin)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .getResponse();
    }

    /** Names of the {@code Access-Control-*} headers on a response (lower case). */
    static List<String> corsHeaders(MockHttpServletResponse response) {
        return Collections.list(java.util.Collections.enumeration(response.getHeaderNames())).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> name.startsWith("access-control-"))
                .sorted()
                .toList();
    }

    /** All values of a header, split on commas, lower case, trimmed. */
    static List<String> values(MockHttpServletResponse response, String header) {
        return response.getHeaders(header).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
    }
}
