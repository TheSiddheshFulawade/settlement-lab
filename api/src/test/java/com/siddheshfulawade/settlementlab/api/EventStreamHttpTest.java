package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Over a real socket: subscribe to the stream, post a transfer, see its outcome arrive. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"settlement.accounts=5", "settlement.opening-balance=1000"})
class EventStreamHttpTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("REQ-API-005 a subscriber receives each processed transfer as a server-sent event")
    void streamsOutcomes() {
        HttpClient client = HttpClient.newHttpClient();
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<HttpResponse<Stream<String>>> stream = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        stream.thenAccept(response -> {
            lines.add("status=" + response.statusCode());
            response.body().forEach(lines::add);
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> lines.contains("status=200"));

        // The subscription is registered once the response has started; post until it is seen.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            post("{\"id\":\"sse-" + System.nanoTime() + "\",\"source\":1,\"target\":2,\"amount\":1}");
            return lines.stream().anyMatch(l -> l.startsWith("data:") && l.contains("\"outcome\":\"POSTED\""));
        });
        post("{\"id\":\"sse-funds\",\"source\":3,\"target\":4,\"amount\":5000}");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> lines.stream().anyMatch(l -> l.contains("\"id\":\"sse-funds\"")));
        assertThat(lines).anyMatch(l -> l.contains("\"id\":\"sse-funds\"") && l.contains("REJECTED_FUNDS"));
        assertThat(lines).anyMatch(l -> l.equals("event:outcome"));
        stream.cancel(true);
    }

    private void post(String json) {
        try {
            HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(json))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
