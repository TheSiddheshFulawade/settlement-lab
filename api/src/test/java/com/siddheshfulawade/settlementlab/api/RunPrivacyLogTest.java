package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * REQ-RUN-006: client IPs are never logged. Logging is turned up to DEBUG for the whole application and
 * everything written to the console is searched for the addresses used by an accepted, a refused (429)
 * and an invalid request.
 *
 * <p>The log level is global to the test JVM, and Spring reuses cached contexts without re-reading it.
 * So this class puts the root level back after it runs and drops its context: otherwise every later
 * class logs at DEBUG (a test that forbids a logged path then fails, and heavy HTTP tests stall).
 */
@SpringBootTest(
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "logging.level.root=DEBUG",
            "settlement.runs.per-ip-per-hour=1",
            "settlement.runs.max-active=10",
            "settlement.runs.global-per-hour=1000"
        })
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class RunPrivacyLogTest {

    /** Invariant: no later test class inherits this class's DEBUG root level. INFO is Spring Boot's default. */
    @AfterAll
    static void restoreRootLogLevel() {
        LoggingSystem.get(RunPrivacyLogTest.class.getClassLoader())
                .setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    private static final String FORGED_FIRST = "203.0.113.5";
    private static final String CLIENT = "198.51.100.77";
    private static final String REMOTE = "192.0.2.55";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    private MockHttpServletResponse post(String body) {
        return mvc.post()
                .uri("/api/v1/runs")
                .with(request -> {
                    request.setRemoteAddr(REMOTE);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", FORGED_FIRST + ", " + CLIENT)
                .content(body)
                .exchange()
                .getResponse();
    }

    @Test
    @DisplayName("REQ-RUN-006 no log line holds a client IP: accepted, refused and invalid requests")
    void clientIpsAreNeverLogged(CapturedOutput output) {
        clock.advance(Duration.ofHours(2));

        MockHttpServletResponse accepted = post(RunHttp.QUICK_RUN);
        MockHttpServletResponse refused = post(RunHttp.QUICK_RUN);
        MockHttpServletResponse invalid = post("{\"ledger\":\"NOPE\"}");

        assertThat(accepted.getStatus()).isEqualTo(202);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(invalid.getStatus()).isEqualTo(400);
        // an IPv6 visitor too (keyed by /64 internally): neither the address nor its prefix may appear
        mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "2001:db8:abcd:12:1:2:3:4")
                .content(RunHttp.QUICK_RUN)
                .exchange();
        MockHttpServletResponse ipv6Refused = mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "2001:db8:abcd:12:9:9:9:9")
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
        assertThat(ipv6Refused.getStatus()).as("same /64 is the same visitor").isEqualTo(429);
        assertThat(output.getAll()).doesNotContain("2001:db8:abcd").doesNotContain("abcd:12");
        assertThat(output.getAll()).as("something was logged at all").isNotBlank();
        assertThat(output.getAll())
                .doesNotContain(FORGED_FIRST)
                .doesNotContain(CLIENT)
                .doesNotContain(REMOTE);
    }
}
