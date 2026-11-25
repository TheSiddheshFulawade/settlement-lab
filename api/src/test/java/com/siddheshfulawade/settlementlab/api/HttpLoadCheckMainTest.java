package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The command-line door: where it may point, and that it refuses before sending anything. Only the guard
 * method itself ever sees a real-looking host (evil.example); run() is only given loopback, .invalid or
 * 192.0.2.x (documentation range), so a bug in the guard can never send a request to a real machine.
 */
class HttpLoadCheckMainTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.evil.example",
                "http://evil.example:8080",
                "http://10.0.0.5:8080",
                "http://localhost.evil.example",
                "http://127.0.0.1.evil.example",
                "http://localhost@evil.example",
                "http://[2001:db8::1]:8080"
            })
    @DisplayName("REQ-API-010 the transfer load refuses a host that is not loopback, without --runs-only")
    void guardRefusesRemoteHostWithoutFlag(String url) {
        assertThatThrownBy(() -> HttpLoadCheckMain.requireAllowedTarget(url, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--runs-only");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://remote.invalid",
                "http://remote.invalid:8080",
                "http://192.0.2.7:8080",
                "http://192.0.2.7"
            })
    @DisplayName("REQ-API-010 run() refuses a non-loopback target with exit 2, before sending anything")
    void runRefusesRemoteHostWithoutFlag(String url) {

        assertThat(HttpLoadCheckMain.run(new String[] {url}))
                .as("exit code of a refusal")
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost.:8080", // trailing dot: not a literal we trust
                "http://2130706433", // decimal IPv4
                "http://0177.0.0.1", // octal IPv4
                "http://0x7f.0.0.1", // hex IPv4
                "http://[::ffff:127.0.0.1]:8080", // IPv4-mapped IPv6
                "http://[0:0:0:0:0:0:0:1]:8080", // expanded IPv6 loopback
                "http://127.0.0.1.:8080",
                "http://localhost%2e.evil.example"
            })
    @DisplayName("REQ-API-010 odd spellings of loopback are refused: only the literal names are trusted")
    void guardRefusesOddSpellings(String url) {
        assertThatThrownBy(() -> HttpLoadCheckMain.requireAllowedTarget(url, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:8080",
                "http://LOCALHOST:8080",
                "http://LocalHost:8080",
                "HTTP://localhost:8080",
                "http://127.0.0.1:9",
                "http://127.1.2.3",
                "http://[::1]:8080"
            })
    @DisplayName("the transfer load is allowed on a loopback address, in any letter case")
    void allowsLoopback(String url) {
        assertThatCode(() -> HttpLoadCheckMain.requireAllowedTarget(url, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("--runs-only is allowed on any host: it does one run and no transfers")
    void runsOnlyAllowsAnyHost() {
        assertThatCode(() -> HttpLoadCheckMain.requireAllowedTarget("https://evil.example", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unreachable server is one clear line and exit 1, with or without --runs-only")
    void unreachableServerExitsOne() throws Exception {
        String url = "http://localhost:" + freePort();

        assertThat(HttpLoadCheckMain.run(new String[] {url})).isEqualTo(1);
        assertThat(HttpLoadCheckMain.run(new String[] {url, "--runs-only"})).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing URL, an unknown option and a missing value are refused with exit 2")
    void badArgumentsAreRefused() {
        assertThat(HttpLoadCheckMain.run(new String[] {})).isEqualTo(2);
        assertThat(HttpLoadCheckMain.run(new String[] {"http://localhost:1", "--turbo"}))
                .isEqualTo(2);
        assertThat(HttpLoadCheckMain.run(new String[] {"http://localhost:1", "--clients"}))
                .isEqualTo(2);
        assertThat(HttpLoadCheckMain.run(new String[] {"http://localhost:1", "--clients", "many"}))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--operations 0",
                "--operations -5",
                "--clients 0",
                "--clients -1",
                "--run-transfers 0",
                "--run-transfers -3",
                "--run-transfers 20001",
                "--operations 0 --runs-only",
                "--run-transfers 99999999 --runs-only"
            })
    @DisplayName("REQ-API-010 sizes that would make the load empty or too big are refused with exit 2 (audit M-1)")
    void sizeOutOfRangeIsRefused(String options) {
        String[] args = ("http://localhost:" + freePortUnused() + " " + options).split(" ");

        assertThat(HttpLoadCheckMain.run(args)).as("exit code for " + options).isEqualTo(2);
    }

    @Test
    @DisplayName("the largest allowed run size (20,000) and the smallest sizes are not refused by the argument check")
    void sizeBoundsAreInclusive() {
        String url = "http://localhost:" + freePortUnused();

        // nothing listens there, so an accepted argument list ends in exit 1 (unreachable), not 2
        assertThat(HttpLoadCheckMain.run(new String[] {url, "--run-transfers", "20000", "--runs-only"}))
                .isEqualTo(1);
        assertThat(HttpLoadCheckMain.run(new String[] {url, "--operations", "1", "--clients", "1"}))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "--runs-only"})
    @DisplayName("REQ-API-010 a URL with a user name or password is refused with exit 2 and never printed")
    void userinfoIsRefusedWithoutEcho(String flag) {
        String url = "http://alice:s3cretpw@localhost:" + freePortUnused();
        String[] args = flag.isEmpty() ? new String[] {url} : new String[] {url, flag};

        Captured captured = capture(() -> HttpLoadCheckMain.run(args));

        assertThat(captured.exit()).isEqualTo(2);
        assertThat(captured.text()).doesNotContain("s3cretpw").doesNotContain("alice");
        assertThatThrownBy(() -> HttpLoadCheckMain.requireAllowedTarget(url, true))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cretpw"));
    }

    @Test
    @DisplayName("REQ-API-010 a 307 from the target is reported and never followed, with or without --runs-only")
    void redirectIsNotFollowed() throws Exception {
        AtomicInteger elsewhereHits = new AtomicInteger();
        HttpServer elsewhere = server(elsewhereHits, null);
        String elsewhereUrl = "http://localhost:" + elsewhere.getAddress().getPort() + "/api/v1/accounts/1";
        HttpServer redirecting = server(new AtomicInteger(), elsewhereUrl);
        try {
            String url = "http://localhost:" + redirecting.getAddress().getPort();

            assertThat(HttpLoadCheckMain.run(new String[] {url})).isEqualTo(1);
            assertThat(HttpLoadCheckMain.run(new String[] {url, "--runs-only"})).isEqualTo(1);

            assertThat(elsewhereHits.get())
                    .as("requests that reached the redirect target")
                    .isZero();
        } finally {
            elsewhere.stop(0);
            redirecting.stop(0);
        }
    }

    // ---- helpers ----

    private record Captured(int exit, String text) {}

    private static Captured capture(java.util.function.IntSupplier call) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(buffer, true);
        System.setOut(both);
        System.setErr(both);
        try {
            int exit = call.getAsInt();
            return new Captured(exit, buffer.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    /** Answers every request with 307 to {@code redirectTo} (or 200 {} when null), counting the hits. */
    private static HttpServer server(AtomicInteger hits, String redirectTo) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            if (redirectTo != null) {
                exchange.getResponseHeaders().add("Location", redirectTo);
                exchange.sendResponseHeaders(307, -1);
            } else {
                byte[] body = "{}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort(); // closed again at once: nothing listens there
        }
    }

    private static int freePortUnused() {
        try {
            return freePort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
