package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

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

/** REQ-RUN-006 end to end: {@code settlement.runs.trusted-proxy-ranges} decides which entry is the visitor. */
@SpringBootTest(
        properties = {
            "settlement.runs.trusted-proxy-ranges=10.0.0.0/8,35.191.0.0/16",
            "settlement.runs.per-ip-per-hour=1",
            "settlement.runs.max-active=20",
            "settlement.runs.global-per-hour=1000",
            "settlement.runs.transfers-per-second=1000000"
        })
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
class RunTrustedProxyHttpTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void emptyWindows() {
        clock.advance(Duration.ofHours(2));
    }

    private MockHttpServletResponse post(String forwardedFor) {
        return mvc.post()
                .uri("/api/v1/runs")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .content(RunHttp.QUICK_RUN)
                .exchange()
                .getResponse();
    }

    @Test
    @DisplayName("REQ-RUN-006 trusted proxies at the right are skipped: the visitor before them is the key")
    void visitorBeforeTrustedProxiesIsTheKey() {
        String visitor = "203.0.113.5";

        assertThat(post("6.6.6.6, " + visitor + ", 10.1.1.1, 35.191.0.7").getStatus())
                .isEqualTo(202);
        MockHttpServletResponse again = post("7.7.7.7, " + visitor + ", 35.191.0.9");

        assertThat(again.getStatus())
                .as("same visitor behind different proxies")
                .isEqualTo(429);
        assertThat(post("7.7.7.7, 203.0.113.250, 10.1.1.1").getStatus())
                .as("another visitor")
                .isEqualTo(202);
    }

    @Test
    @DisplayName("REQ-RUN-006 when every entry is a trusted proxy the remote address is the key")
    void allTrustedUsesRemoteAddress() {
        assertThat(post("10.1.1.1, 35.191.0.7").getStatus()).isEqualTo(202);

        assertThat(post("10.2.2.2").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("REQ-RUN-006 an entry that is not an IP, reached by the walk, means the remote address")
    void garbageUsesRemoteAddress() {
        assertThat(post("203.0.113.77, not-an-ip, 10.1.1.1").getStatus()).isEqualTo(202);
        assertThat(post("198.51.100.3, also-garbage").getStatus())
                .as("the same remote address again, whatever else is in the header")
                .isEqualTo(429);
    }
}
