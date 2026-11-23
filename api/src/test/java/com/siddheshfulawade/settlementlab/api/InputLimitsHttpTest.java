package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.siddheshfulawade.settlementlab.domain.AccountId;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * REQ-API-009 over a real server: a body over 16 KB (16,384 bytes) is 413 body-too-large, whether or not
 * it has a Content-Length (chunked), on /runs and /transfers, and nothing is submitted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.max-active=20",
            "settlement.runs.per-ip-per-hour=5",
            "settlement.runs.global-per-hour=1000"
        })
class InputLimitsHttpTest {

    private static final int LIMIT = 16_384;

    @LocalServerPort
    int port;

    @Autowired
    SettlementRuntime runtime;

    /** Valid JSON, padded with trailing spaces to exactly {@code bytes} bytes. */
    private static String padded(String json, int bytes) {
        return json + " ".repeat(bytes - json.getBytes(StandardCharsets.UTF_8).length);
    }

    private HttpResponse<String> send(String path, HttpRequest.BodyPublisher body, String origin, String forwardedFor)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(body);
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher withLength(String body) {
        return HttpRequest.BodyPublishers.ofString(body);
    }

    /** No Content-Length: the client sends the body as chunks. */
    private static HttpRequest.BodyPublisher chunked(String body) {
        return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(response.body(), "$.type"))
                .isEqualTo(RunHttp.PROBLEM_BASE + "body-too-large");
        assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(413);
    }

    @Test
    @DisplayName("REQ-API-009 a run request of exactly 16,384 bytes is accepted; 16,385 is 413")
    void runBodyBoundary() throws Exception {
        String ip = RunHttp.nextIp();

        HttpResponse<String> atLimit = send("/api/v1/runs", withLength(padded(RunHttp.QUICK_RUN, LIMIT)), null, ip);
        HttpResponse<String> over = send("/api/v1/runs", withLength(padded(RunHttp.QUICK_RUN, LIMIT + 1)), null, ip);

        assertThat(atLimit.statusCode()).as(atLimit.body()).isEqualTo(202);
        assertTooLarge(over);
    }

    @Test
    @DisplayName("REQ-API-009 a chunked run request over 16 KB is 413 even without Content-Length")
    void chunkedRunBodyIsRejected() throws Exception {
        String ip = RunHttp.nextIp();

        assertTooLarge(send("/api/v1/runs", chunked(padded(RunHttp.QUICK_RUN, LIMIT + 1)), null, ip));
        assertTooLarge(send("/api/v1/runs", chunked(padded(RunHttp.QUICK_RUN, 200_000)), null, ip));
        HttpResponse<String> atLimit = send("/api/v1/runs", chunked(padded(RunHttp.QUICK_RUN, LIMIT)), null, ip);
        assertThat(atLimit.statusCode()).as(atLimit.body()).isEqualTo(202);
    }

    @Test
    @DisplayName("REQ-API-009 an oversized run request starts nothing and uses no rate-limit slot")
    void oversizedRunRequestUsesNoSlot() throws Exception {
        String ip = RunHttp.nextIp();
        for (int i = 0; i < 6; i++) {
            assertTooLarge(send("/api/v1/runs", withLength(padded(RunHttp.QUICK_RUN, LIMIT + 1)), null, ip));
            assertTooLarge(send("/api/v1/runs", chunked(padded(RunHttp.QUICK_RUN, LIMIT + 1)), null, ip));
        }

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> accepted = send("/api/v1/runs", withLength(RunHttp.QUICK_RUN), null, ip);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        }
        assertThat(send("/api/v1/runs", withLength(RunHttp.QUICK_RUN), null, ip).statusCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("REQ-API-009 an oversized transfer is 413, with or without Content-Length, and nothing is submitted")
    void oversizedTransferIsRejected() throws Exception {
        long submitted = runtime.engine().metrics().submitted();
        long balance = runtime.engine().snapshot().get(AccountId.of(1)).minorUnits();
        String json = "{\"id\":\"big-1\",\"source\":1,\"target\":2,\"amount\":1}";

        assertTooLarge(send("/api/v1/transfers", withLength(padded(json, LIMIT + 1)), null, null));
        assertTooLarge(send("/api/v1/transfers", chunked(padded(json, LIMIT + 1)), null, null));

        assertThat(runtime.engine().metrics().submitted()).isEqualTo(submitted);
        assertThat(runtime.engine().snapshot().get(AccountId.of(1)).minorUnits())
                .isEqualTo(balance);
        HttpResponse<String> atLimit = send("/api/v1/transfers", withLength(padded(json, LIMIT)), null, null);
        assertThat(atLimit.statusCode()).as(atLimit.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("REQ-RUN-008 a 413 answer carries the CORS headers for an allowed origin and none for a foreign one")
    void tooLargeAnswerHasCorsHeaders() throws Exception {
        String body = padded(RunHttp.QUICK_RUN, LIMIT + 1);

        HttpResponse<String> allowed =
                send("/api/v1/runs", withLength(body), "https://siddheshfulawade.com", RunHttp.nextIp());
        HttpResponse<String> foreign = send("/api/v1/runs", withLength(body), "https://evil.example", RunHttp.nextIp());

        assertTooLarge(allowed);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("https://siddheshfulawade.com");
        assertThat(foreign.headers().map().keySet())
                .noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
    }
}
