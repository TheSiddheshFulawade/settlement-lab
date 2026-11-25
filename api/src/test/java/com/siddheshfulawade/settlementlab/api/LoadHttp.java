package com.siddheshfulawade.settlementlab.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * The only door the load check uses: {@code java.net.http} against a base URL, nothing else. It counts
 * every request by status and by route, so the report can say what was really sent (and a test can prove
 * that "runs only" sends nothing but runs).
 *
 * <p>The server is not trusted to behave: no proxy is used, a redirect is never followed, every wait
 * (the answer and the whole body) ends at one deadline, and a body larger than the cap is refused.
 */
final class LoadHttp {

    /** {@code status} is -1 when the request itself failed (no answer), with the reason in {@code body}. */
    record Response(int status, String body, HttpHeaders headers) {
        Response {
            headers = headers == null ? HttpHeaders.of(Map.of(), (a, b) -> true) : headers;
        }

        /** The Retry-After header in seconds, 1 if absent, never more than 10 (a bounded wait). */
        long retryAfterSeconds() {
            return headers.firstValue("Retry-After").map(LoadHttp::parseSeconds).orElse(1L);
        }
    }

    /** What an event stream delivered, and whether the server closed it before the limit. */
    record Stream(int status, String text, boolean closedByServer) {}

    /** The whole answer to one request, or its failure, within {@code REQUEST_TIMEOUT}. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_BODY_BYTES = 1_000_000;

    private final String base;
    private final Duration requestLimit;
    private final int maxBodyBytes;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(ProxySelector.of(null)) // never a proxy, whatever the machine is configured with
            .followRedirects(HttpClient.Redirect.NEVER) // a 307 is an answer, not an instruction
            .build();
    private final LongAdder requests = new LongAdder();
    private final Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> routes = new ConcurrentHashMap<>();

    LoadHttp(String baseUrl) {
        this(baseUrl, REQUEST_TIMEOUT, MAX_BODY_BYTES);
    }

    /** An event stream may be twice {@code maxBodyBytes}. */
    LoadHttp(String baseUrl, Duration requestLimit, int maxBodyBytes) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestLimit = requestLimit;
        this.maxBodyBytes = maxBodyBytes;
    }

    Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)), "GET", path);
    }

    Response post(String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return send(request, "POST", path);
    }

    private Response send(HttpRequest.Builder request, String method, String path) {
        count(method, path);
        try {
            Answer answer = fetch(request.timeout(requestLimit).build(), requestLimit, maxBodyBytes);
            if (!answer.complete()) {
                throw new IOException("timed out reading the body");
            }
            statusSeen(String.valueOf(answer.status()));
            return new Response(answer.status(), answer.text(), answer.headers());
        } catch (IOException e) {
            statusSeen("no-answer");
            return new Response(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            statusSeen("no-answer");
            return new Response(-1, "interrupted", null);
        }
    }

    /**
     * Opens a server-sent event stream and reads it to the end. A watchdog closes the stream after
     * {@code limit}, so a server that never closes cannot hang the check ({@code closedByServer} is then false).
     */
    Stream readEvents(String path, Duration limit) {
        count("GET", path);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept", "text/event-stream")
                .timeout(requestLimit)
                .build();
        try {
            Answer answer = fetch(request, limit, 2 * maxBodyBytes);
            statusSeen(String.valueOf(answer.status()));
            return new Stream(answer.status(), answer.complete() ? answer.text() : "", answer.complete());
        } catch (IOException e) {
            statusSeen("no-answer");
            return new Stream(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            statusSeen("no-answer");
            return new Stream(-1, "interrupted", false);
        }
    }

    /** {@code complete} is false when the body was cut at {@code bodyLimit} instead of ending by itself. */
    private record Answer(int status, String text, boolean complete, HttpHeaders headers) {}

    /**
     * Invariant: no wait here is unbounded. The answer must arrive within {@code requestLimit} (then the
     * request is cancelled); the body is read under a watchdog for {@code bodyLimit}, and never past
     * {@code maxBytes}, after which the connection is closed.
     */
    private Answer fetch(HttpRequest request, Duration bodyLimit, int maxBytes)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = future.get(requestLimit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("timed out waiting for the answer");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        InputStream in = response.body();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(bodyLimit); // a deadline for the whole body, not a synchronisation
                timedOut.set(true);
                in.close();
            } catch (InterruptedException | IOException ignored) {
                // the body ended first, or it is already closed
            }
        });
        try (in) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("response larger than " + maxBytes + " bytes");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new Answer(response.statusCode(), text, !timedOut.get(), response.headers());
        } catch (IOException e) {
            if (timedOut.get()) {
                return new Answer(response.statusCode(), "", false, response.headers());
            }
            throw e;
        } finally {
            watchdog.interrupt();
        }
    }

    long requests() {
        return requests.sum();
    }

    /** Status code (or "no-answer") to count, in order. */
    Map<String, Long> statusCounts() {
        return snapshot(statuses);
    }

    /** "METHOD route" to count; ids in the path are replaced by {id}. */
    Map<String, Long> routeCounts() {
        return snapshot(routes);
    }

    private void count(String method, String path) {
        requests.increment();
        String route =
                path.replaceAll("/accounts/\\d+", "/accounts/{id}").replaceAll("/runs/[A-Za-z0-9-]+", "/runs/{id}");
        routes.computeIfAbsent(method + " " + route, k -> new LongAdder()).increment();
    }

    private void statusSeen(String status) {
        statuses.computeIfAbsent(status, k -> new LongAdder()).increment();
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counts) {
        Map<String, Long> sorted = new TreeMap<>();
        counts.forEach((k, v) -> sorted.put(k, v.sum()));
        return sorted;
    }

    private static long parseSeconds(String value) {
        try {
            return Math.max(0, Math.min(10, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
