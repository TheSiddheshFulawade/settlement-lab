package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * REQ-API-010: with the {@code prod} profile only /api/v1/runs/** and /actuator/health are served.
 * The check walks every request mapping the application registers, so a controller added later cannot
 * slip through; the default profile is shown to keep the local endpoints.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=prod",
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.per-ip-per-hour=1000"
        })
@AutoConfigureMockMvc
class ProdProfileTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    static Set<String> registeredPatterns(ApplicationContext context) {
        Set<String> patterns = new TreeSet<>();
        for (RequestMappingInfoHandlerMapping mapping :
                context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                if (info.getPathPatternsCondition() != null) {
                    patterns.addAll(info.getPathPatternsCondition().getPatternValues());
                }
            }
        }
        return patterns;
    }

    private static boolean allowedInProd(String pattern) {
        return pattern.equals("/api/v1/runs")
                || pattern.startsWith("/api/v1/runs/")
                || pattern.equals("/actuator/health")
                || pattern.startsWith("/actuator/health/")
                || pattern.equals("/error"); // Spring's own error page, only reached by an internal dispatch
    }

    private MockHttpServletResponse call(HttpMethod method, String path) {
        return mvc.method(method)
                .uri(path)
                .accept(MediaType.ALL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange()
                .getResponse();
    }

    @Test
    @DisplayName("REQ-API-010 the runs endpoints and health are served in prod")
    void runsAndHealthWork() {
        List<String> patterns = List.copyOf(registeredPatterns(context));

        assertThat(patterns).contains("/api/v1/runs", "/actuator/health");
        MockHttpServletResponse started = mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", RunHttp.nextIp())
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
        assertThat(started.getStatus()).isEqualTo(202);
        assertThat(call(HttpMethod.GET, "/actuator/health").getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/r-00000000").getStatus())
                .isEqualTo(404); // the run, not the route
    }

    @Test
    @DisplayName("REQ-API-010 every registered request mapping outside the allowed two answers 404, with any method")
    void everyOtherRegisteredMappingIs404() {
        Set<String> others = new TreeSet<>(registeredPatterns(context));
        others.removeIf(ProdProfileTest::allowedInProd);

        for (String pattern : others) {
            String path =
                    pattern.replaceAll("\\{[^}]*}", "1").replace("**", "x").replace("*", "x");
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
                assertThat(call(method, path).getStatus())
                        .as("%s %s (registered as %s)", method, path, pattern)
                        .isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("REQ-API-010 the local endpoints and the other actuator paths answer 404 in prod, not 405 or 401")
    void localEndpointsAreGone() {
        List<String> paths = List.of(
                "/api/v1/transfers",
                "/api/v1/accounts/1",
                "/api/v1/metrics",
                "/api/v1/events",
                "/actuator",
                "/actuator/env",
                "/actuator/info",
                "/actuator/beans",
                "/actuator/metrics",
                "/actuator/mappings",
                "/actuator/health/db",
                "/",
                "/favicon.ico");
        for (String path : paths) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
                assertThat(call(method, path).getStatus())
                        .as("%s %s", method, path)
                        .isEqualTo(404);
            }
        }
    }
}
