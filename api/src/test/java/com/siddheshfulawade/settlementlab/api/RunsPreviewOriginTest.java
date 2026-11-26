package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * L-8: {@code settlement.runs.preview-origin} must be one exact browser origin, or nothing. A value that
 * can never equal an {@code Origin} header (or that would widen the allow-list) stops the application at
 * start-up instead of silently doing nothing, and the production origin given as the preview origin must
 * not crash the CORS filter (a duplicate in {@code Set.of}).
 */
class RunsPreviewOriginTest {

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "null",
                "*",
                "http://develop.portfolio-7qt.pages.dev",
                "https://develop.portfolio-7qt.pages.dev/",
                "https://develop.portfolio-7qt.pages.dev/path",
                "https://develop.portfolio-7qt.pages.dev?x=1",
                "https://develop.portfolio-7qt.pages.dev#frag",
                "https://user@develop.portfolio-7qt.pages.dev",
                "https://user:secret@develop.portfolio-7qt.pages.dev",
                "https://Develop.Portfolio-7qt.pages.dev",
                "https://develop .pages.dev",
                "https://a.pages.dev,https://b.pages.dev",
                "https://a.pages.dev https://b.pages.dev",
                "https://",
                "https://*.pages.dev",
                "develop.portfolio-7qt.pages.dev",
                "https://example.pages.dev:443"
            })
    @DisplayName("REQ-RUN-008 a preview origin that is not one exact https origin is refused")
    void invalidPreviewOriginIsRefused(String value) {
        assertThatThrownBy(() -> RunsProperties.defaults().withPreviewOrigin(value))
                .as("preview-origin %s", value)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "",
                "   ",
                "https://develop.portfolio-7qt.pages.dev",
                "https://example.pages.dev:8443",
                RunCorsFilter.PRODUCTION_ORIGIN
            })
    @DisplayName(
            "REQ-RUN-008 empty, blank, one https origin (explicit non-default port too) and the production origin are accepted")
    void validPreviewOriginIsAccepted(String value) {
        assertThatCode(() -> RunsProperties.defaults().withPreviewOrigin(value)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "REQ-RUN-008 the production origin as the preview origin builds the CORS filter and allows only itself")
    void productionOriginAsPreviewOriginDoesNotBreakTheFilter() {
        assertThatCode(() -> new RunCorsFilter(RunCorsFilter.PRODUCTION_ORIGIN)).doesNotThrowAnyException();
    }

    private static ConfigurableApplicationContext start(String previewOrigin) {
        return new SpringApplicationBuilder(SettlementApiApplication.class)
                .run("--server.port=0", "--settlement.runs.preview-origin=" + previewOrigin);
    }

    @Test
    @DisplayName("REQ-RUN-008 start-up fails for a wildcard preview origin")
    void wildcardStopsTheApplication() {
        assertThatThrownBy(() -> start("*").close())
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preview-origin");
    }

    @Test
    @DisplayName("REQ-RUN-008 start-up succeeds with the production origin as preview origin and CORS still works")
    void productionOriginAsPreviewOriginStarts() throws Exception {
        try (ConfigurableApplicationContext context = start(RunCorsFilter.PRODUCTION_ORIGIN)) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                                    .header("Origin", RunCorsFilter.PRODUCTION_ORIGIN)
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains(RunCorsFilter.PRODUCTION_ORIGIN);
        }
    }
}
