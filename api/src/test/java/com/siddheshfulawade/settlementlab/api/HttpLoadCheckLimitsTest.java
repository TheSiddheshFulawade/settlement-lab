package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The load check must never hang, never follow a redirect, never use a proxy and never buffer an
 * unbounded body, whatever the server does (audit L-a, L-b, L-c). The servers here misbehave on purpose.
 */
class HttpLoadCheckLimitsTest {

    private static final Duration SHORT = Duration.ofSeconds(1);
    /** Generous: the point is "it ended", not how fast. */
    private static final Duration ENDS_WITHIN = Duration.ofSeconds(15);

    @Test
    @DisplayName("REQ-API-010 a server that never answers is given up on after the limit")
    void silentServerTimesOut() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.SILENT)) {
            LoadHttp http = new LoadHttp(server.url(), SHORT, 10_000);

            LoadHttp.Response response = assertTimeoutPreemptively(ENDS_WITHIN, () -> http.get("/x"));

            assertThat(response.status()).isEqualTo(-1);
            assertThat(http.statusCounts()).containsEntry("no-answer", 1L);
        }
    }

    @Test
    @DisplayName("REQ-API-010 a body that drips one byte at a time forever is cut off by the overall limit")
    void dripBodyTimesOut() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.DRIP)) {
            LoadHttp http = new LoadHttp(server.url(), SHORT, 1_000_000);

            LoadHttp.Response response = assertTimeoutPreemptively(ENDS_WITHIN, () -> http.get("/x"));

            assertThat(response.status()).isEqualTo(-1);
            assertThat(response.body()).containsIgnoringCase("time");
            Awaitility.await("the server sees the connection closed")
                    .atMost(ENDS_WITHIN)
                    .until(() -> server.finishedWriters() == 1);
        }
    }

    @Test
    @DisplayName("REQ-API-010 a body larger than the cap is refused and the connection is closed")
    void floodBodyIsCapped() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.FLOOD)) {
            LoadHttp http = new LoadHttp(server.url(), Duration.ofSeconds(10), 10_000);

            LoadHttp.Response response = assertTimeoutPreemptively(ENDS_WITHIN, () -> http.get("/x"));

            assertThat(response.status()).isEqualTo(-1);
            assertThat(response.body()).containsIgnoringCase("larger");
            assertThat(response.body().length()).isLessThan(1_000);
            Awaitility.await("the server stopped flooding because the client closed")
                    .atMost(ENDS_WITHIN)
                    .until(() -> server.finishedWriters() == 1);
            assertThat(server.bytesWritten())
                    .as("bytes the server managed to send")
                    .isLessThan(64L * 1024 * 1024);
        }
    }

    @Test
    @DisplayName("REQ-RUN-003 an event stream that floods is cut at its cap, not read to the end")
    void floodedEventStreamIsCapped() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.FLOOD)) {
            LoadHttp http = new LoadHttp(server.url(), Duration.ofSeconds(10), 10_000);

            LoadHttp.Stream stream =
                    assertTimeoutPreemptively(ENDS_WITHIN, () -> http.readEvents("/events", Duration.ofSeconds(10)));

            assertThat(stream.closedByServer()).isFalse();
            assertThat(stream.text().length()).as("text kept").isLessThanOrEqualTo(2 * 10_000);
            Awaitility.await("the server stopped flooding because the client closed")
                    .atMost(ENDS_WITHIN)
                    .until(() -> server.finishedWriters() == 1);
        }
    }

    @Test
    @DisplayName("REQ-RUN-003 an event stream that drips is cut at the stream limit and says it was not closed")
    void drippedEventStreamEndsAtItsLimit() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.DRIP)) {
            LoadHttp http = new LoadHttp(server.url(), Duration.ofSeconds(10), 1_000_000);

            LoadHttp.Stream stream =
                    assertTimeoutPreemptively(ENDS_WITHIN, () -> http.readEvents("/events", Duration.ofSeconds(1)));

            assertThat(stream.closedByServer()).isFalse();
        }
    }

    @Test
    @DisplayName("REQ-API-010 a 307 answer is returned as it is and the Location is never requested")
    void redirectIsNotFollowed() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer target = localServer(exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        });
        HttpServer redirecting = localServer(exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://localhost:" + target.getAddress().getPort() + "/landed");
            exchange.sendResponseHeaders(307, -1);
        });
        try {
            LoadHttp http =
                    new LoadHttp("http://localhost:" + redirecting.getAddress().getPort());

            LoadHttp.Response get = http.get("/start");
            LoadHttp.Response post = http.post("/start", "{}");

            assertThat(get.status()).isEqualTo(307);
            assertThat(post.status()).isEqualTo(307);
            assertThat(targetHits.get()).isZero();
        } finally {
            target.stop(0);
            redirecting.stop(0);
        }
    }

    @Test
    @DisplayName("REQ-API-010 the client never asks a proxy selector, so a proxy setting cannot redirect the load")
    void proxyIsNeverConsulted() throws Exception {
        HttpServer server = localServer(exchange -> exchange.sendResponseHeaders(204, -1));
        ProxySelector previous = ProxySelector.getDefault();
        List<URI> asked = new CopyOnWriteArrayList<>();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<java.net.Proxy> select(URI uri) {
                asked.add(uri);
                return List.of(java.net.Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {}
        });
        try {
            LoadHttp http =
                    new LoadHttp("http://localhost:" + server.getAddress().getPort());

            assertThat(http.get("/x").status()).isEqualTo(204);

            assertThat(asked)
                    .as("URIs the default proxy selector was asked about")
                    .isEmpty();
        } finally {
            ProxySelector.setDefault(previous);
            server.stop(0);
        }
    }

    @Test
    @DisplayName("REQ-API-010 when the load gives up at its deadline, no client keeps sending afterwards")
    void clientsStopSendingAfterTheDeadline() throws Exception {
        try (MisbehavingServer server = new MisbehavingServer(MisbehavingServer.Mode.SILENT)) {
            LoadHttp http = new LoadHttp(server.url(), Duration.ofMillis(400), 10_000);
            TransferLoad load = new TransferLoad(http, 4, Duration.ofSeconds(1));
            List<TransferPlan.Op> ops = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                ops.add(new TransferPlan.Op("t" + i, 1, 2, 5, TransferPlan.Kind.NORMAL, 1));
            }

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> load.run(ops, 1L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not finish");

            int atGiveUp = server.connections();
            Awaitility.await("no new request starts")
                    .during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> server.connections() <= atGiveUp + 4);
        }
    }

    private static HttpServer localServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            handler.handle(exchange);
            exchange.close();
        });
        server.start();
        return server;
    }
}
