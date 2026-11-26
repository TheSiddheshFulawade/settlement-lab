package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * L-12: on a detected cloud platform Spring Boot defaults {@code server.forward-headers-strategy} to
 * {@code native}: Tomcat's remote-ip valve then rewrites {@code X-Forwarded-For} before our code reads
 * it, and the visitor key changes. The application must pin the strategy itself so the per-IP limit never
 * depends on the platform. Real HTTP, because Tomcat's own valve is what is being exercised.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.cloud-platform=kubernetes",
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.per-ip-per-hour=5",
            "settlement.runs.max-active=20",
            "settlement.runs.global-per-hour=1000"
        })
class ForwardedHeadersHttpTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName(
            "REQ-RUN-006 on a cloud platform the last X-Forwarded-For entry is still the key: the 6th start is 429")
    void forgedFirstEntriesDoNotDodgeTheLimitOnACloudPlatform() throws Exception {
        String visitor = RunHttp.nextIp();
        List<Integer> statuses = new ArrayList<>();

        for (int i = 1; i <= 6; i++) {
            statuses.add(RunHttp.post(port, RunHttp.QUICK_RUN, "203.0.113." + i + ", " + visitor)
                    .statusCode());
        }

        assertThat(statuses)
                .as("five starts, then the limit, whatever the forged first entry says")
                .containsExactly(202, 202, 202, 202, 202, 429);
    }
}
