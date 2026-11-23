package com.siddheshfulawade.settlementlab.api;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** An open GET whose body is read as it arrives, with the status and headers kept (unlike RawStream). */
final class LiveStream implements AutoCloseable {

    private final StringBuffer text = new StringBuffer();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicReference<InputStream> body = new AtomicReference<>();
    private final CountDownLatch headersArrived = new CountDownLatch(1);
    private volatile int status;
    private volatile HttpHeaders headers = HttpHeaders.of(Map.of(), (a, b) -> true);

    static LiveStream open(int port, String path, Map<String, String> requestHeaders) {
        LiveStream stream = new LiveStream();
        Thread.ofVirtual().start(() -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", "text/event-stream");
                requestHeaders.forEach(request::header);
                HttpResponse<InputStream> response =
                        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                stream.status = response.statusCode();
                stream.headers = response.headers();
                stream.body.set(response.body());
                stream.headersArrived.countDown();
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
                stream.headersArrived.countDown();
                stream.ended.set(true);
            }
        });
        return stream;
    }

    /** Waits for the response headers; false if they did not come in time. */
    boolean awaitHeaders(Duration limit) throws InterruptedException {
        return headersArrived.await(limit.toMillis(), TimeUnit.MILLISECONDS) && status != 0;
    }

    int status() {
        return status;
    }

    HttpHeaders headers() {
        return headers;
    }

    String text() {
        return text.toString();
    }

    boolean ended() {
        return ended.get();
    }

    @Override
    public void close() {
        InputStream in = body.get();
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // already closed
            }
        }
    }
}
