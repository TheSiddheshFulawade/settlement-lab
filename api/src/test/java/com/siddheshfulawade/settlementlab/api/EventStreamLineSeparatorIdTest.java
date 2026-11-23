package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** A transfer id with a line separator must not break the stream for anyone (REQ-API-005). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"settlement.accounts=5", "settlement.opening-balance=1000000"})
class EventStreamLineSeparatorIdTest {

    @LocalServerPort
    int port;

    @Autowired
    SettlementRuntime runtime;

    @ParameterizedTest(name = "[{index}] id with separator")
    @ValueSource(strings = {"a\nb", "a\rb", "a\r\nb", "bad\nevent: forged", "bad\r\nevent: forged"})
    @DisplayName("REQ-API-005 an id with a line separator keeps subscribers connected and is delivered intact")
    void separatorInIdDoesNotDropSubscribers(String badId) throws Exception {
        String unique = Long.toString(System.nanoTime());
        String id = badId + unique;
        RawStream stream = RawStream.open("http://localhost:" + port + "/api/v1/events");
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.text().contains("connected"));
        // The subscription exists once the stream is open; post until the warm-up event is seen.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            post("warm-" + System.nanoTime());
            return stream.text().contains("\"id\":\"warm-");
        });

        // Over HTTP such an id is now a 400 (REQ-API-009, see TransferInputTest), so the regression is kept by
        // handing the transfer to the engine directly: the stream must still cope with an id the engine accepted.
        runtime.engine().submit(new Transfer(TransferId.of(id), AccountId.of(1), AccountId.of(2), Money.of(1)));
        post("next-" + unique);

        String escaped = id.replace("\r", "\\r").replace("\n", "\\n");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(stream.text())
                        .as("the next normal transfer's event must still arrive (stream ended=%s)", stream.ended())
                        .contains("\"id\":\"next-" + unique + "\""));
        assertThat(stream.ended()).as("subscriber must stay connected").isFalse();
        assertThat(stream.text())
                .as("the separator id's event must reach the subscriber, id intact in the JSON data")
                .contains("\"id\":\"" + escaped + "\"");
        assertThat(stream.text().split("\r\n|\r|\n"))
                .as("no injected event line")
                .noneMatch(line -> line.startsWith("event: forged") || line.startsWith("event:forged"));
        stream.close();
    }

    private void post(String id) {
        try {
            String json = "{\"id\":\"" + id.replace("\r", "\\r").replace("\n", "\\n")
                    + "\",\"source\":1,\"target\":2,\"amount\":1}";
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

    /** Reads the response chars as they arrive, with no line splitting, so injected lines show. */
    static final class RawStream {
        private final StringBuffer text = new StringBuffer();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicReference<InputStream> body = new AtomicReference<>();

        static RawStream open(String url) {
            RawStream stream = new RawStream();
            Thread.ofVirtual().start(() -> {
                try {
                    HttpResponse<InputStream> response = HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(URI.create(url))
                                            .header("Accept", "text/event-stream")
                                            .build(),
                                    HttpResponse.BodyHandlers.ofInputStream());
                    stream.body.set(response.body());
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        int c;
                        while ((c = reader.read()) != -1) {
                            stream.text.append((char) c);
                        }
                    }
                } catch (Exception ignored) {
                    // a closed stream ends the reader
                } finally {
                    stream.ended.set(true);
                }
            });
            return stream;
        }

        String text() {
            return text.toString();
        }

        boolean ended() {
            return ended.get();
        }

        void close() throws Exception {
            InputStream in = body.get();
            if (in != null) {
                in.close();
            }
        }
    }
}
