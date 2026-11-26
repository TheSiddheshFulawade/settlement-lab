package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * N-3b and N-3c, with {@code server.tomcat.connection-timeout} lowered to 1 s so the tests are short: a client
 * that announces a 100-byte body and sends one byte must not hold its connection (a thread) open for the
 * default 60 s, and the answer is a close or a 4xx, never a 500. And the same short timeout must not cut a
 * live event stream: a run of about 3 s still delivers its report.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.tomcat.connection-timeout=1s",
            "settlement.runs.transfers-per-second=1000",
            "settlement.runs.per-ip-per-hour=100",
            "settlement.runs.max-active=10",
            "settlement.runs.global-per-hour=1000"
        })
class StalledBodyHttpTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName(
            "REQ-API-009 a POST that stalls after one body byte is closed or answered 4xx within seconds, never a 500")
    void stalledSmallBodyDoesNotHoldTheConnection() throws Exception {
        long startedAt = System.nanoTime();
        String outcome;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(8_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/v1/runs HTTP/1.1\r\nHost: localhost:" + port
                            + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            outcome = firstLine(socket);
        }
        long seconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();

        assertThat(outcome)
                .as("the server must close the stalled connection or answer a client error (not hang, not 500)")
                .satisfiesAnyOf(
                        line -> assertThat(line).isEqualTo("CLOSED"),
                        line -> assertThat(line).matches("HTTP/1\\.1 4\\d\\d.*"));
        assertThat(seconds)
                .as("seconds until the server gave up (limit about 3, generous margin)")
                .isLessThan(6);
    }

    /** The status line, or CLOSED if the server closed or reset first, or TIMEOUT if it did neither. */
    private static String firstLine(Socket socket) {
        try {
            String line = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine();
            return line == null ? "CLOSED" : line;
        } catch (SocketTimeoutException stillOpen) {
            return "TIMEOUT";
        } catch (IOException reset) {
            return "CLOSED";
        }
    }

    @Test
    @DisplayName("REQ-RUN-004 with a 1 s connection timeout a run of about 3 s still streams its report")
    void liveStreamSurvivesTheShortTimeout() throws Exception {
        String runId = RunHttp.runIdOf(RunHttp.post(port, RunHttp.FLAKY_RUN, RunHttp.nextIp()));

        try (LiveStream stream = LiveStream.open(port, "/api/v1/runs/" + runId + "/events", Map.of())) {
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        List<SseText.Event> events = SseText.parse(stream.text());
                        assertThat(events).extracting(SseText.Event::name).contains("report");
                    });
        }
    }
}
