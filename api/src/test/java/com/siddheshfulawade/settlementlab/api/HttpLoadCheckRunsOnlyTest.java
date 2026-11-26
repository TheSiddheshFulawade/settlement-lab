package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The prod profile serves only runs (REQ-API-010). The runs-only mode must work against it, and send
 * exactly one run and nothing else: no transfer, account or metrics request, which prod answers 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=prod")
class HttpLoadCheckRunsOnlyTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName(
            "REQ-API-010 runs-only against the prod profile: one run, all checks pass, no other endpoint is touched")
    void runsOnlyAgainstProd() {
        int exit = HttpLoadCheckMain.run(
                new String[] {"http://localhost:" + port, "--runs-only", "--run-transfers", "800"});

        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("REQ-API-010 the runs-only request log is one POST of a run, its event stream and one GET of it")
    void runsOnlySendsOnlyRunRequests() {
        HttpLoadCheck.Options options = new HttpLoadCheck.Options(0, 1, 1L, 800, "CAS", "PLATFORM", false, true);

        HttpLoadCheck.Report report = HttpLoadCheck.run("http://localhost:" + port, options);

        assertThat(report.passed()).as(report.checks().toString()).isTrue();
        assertThat(report.routeCounts())
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("POST /api/v1/runs", 1L),
                        org.assertj.core.api.Assertions.entry("GET /api/v1/runs/{id}/events", 1L),
                        org.assertj.core.api.Assertions.entry("GET /api/v1/runs/{id}", 1L));
        assertThat(report.statusCounts()).containsOnlyKeys(List.of("202", "200").toArray(String[]::new));
    }
}
