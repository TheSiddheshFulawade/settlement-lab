package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Start-up and restart behaviour of the runs API (REQ-RUN-005, 006). */
class RunStartupTest {

    private static ConfigurableApplicationContext run(String... extraArgs) {
        String[] args = new String[extraArgs.length + 2];
        args[0] = "--server.port=0";
        args[1] = "--settlement.runs.transfers-per-second=1000000";
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        return new SpringApplicationBuilder(SettlementApiApplication.class).run(args);
    }

    private static int port(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {"not-a-cidr", "10.0.0.0/33", "10.0.0.0/-1", "999.0.0.0/8", "2001:db8::/129", "10.0.0.0/8,oops"})
    @DisplayName("REQ-RUN-006 an invalid trusted proxy range stops the application at start-up")
    void invalidCidrFailsStartup(String ranges) {
        assertThatThrownBy(() ->
                        run("--settlement.runs.trusted-proxy-ranges=" + ranges).close())
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("REQ-RUN-006 valid IPv4 and IPv6 ranges, comma separated, start fine")
    void validRangesStart() {
        try (ConfigurableApplicationContext context =
                run("--settlement.runs.trusted-proxy-ranges=10.0.0.0/8,35.191.0.0/16,2001:db8::/32")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    @DisplayName("REQ-RUN-005 after a restart every earlier run id answers 404")
    void oldIdsAreGoneAfterRestart() throws Exception {
        String runId;
        try (ConfigurableApplicationContext first = run()) {
            int port = port(first);
            runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.QUICK_RUN, RunHttp.nextIp()));
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        HttpResponse<String> status = RunHttp.get(port, "/api/v1/runs/" + runId);
                        return status.statusCode() == 200 && "DONE".equals(JsonPath.read(status.body(), "$.status"));
                    });
        }

        try (ConfigurableApplicationContext second = run()) {
            for (String path : new String[] {"/api/v1/runs/" + runId, "/api/v1/runs/" + runId + "/events"}) {
                HttpResponse<String> response = RunHttp.get(port(second), path);

                assertThat(response.statusCode()).as(path).isEqualTo(404);
                assertThat((String) JsonPath.read(response.body(), "$.type"))
                        .isEqualTo(RunHttp.PROBLEM_BASE + "run-not-found");
            }
        }
    }
}
