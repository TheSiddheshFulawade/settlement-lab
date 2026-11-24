package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-API-009 for bodies the cap used to skip: PUT, PATCH and DELETE with a form body. Spring's form
 * content filter parses those into memory before any handler runs, so a 16 KB limit that only covers POST
 * is not a limit. Each concrete subclass runs this against one profile.
 *
 * <p>Raw sockets, not {@code HttpClient}: the server may answer and close while the client is still
 * sending, and the status line must still be read. A reset after the status line is not a failure; a
 * missing status line, or one that is not 413, is.
 */
abstract class FormBodyLimitContract {

    private static final int LIMIT = BodyLimitFilter.MAX_BYTES;
    private static final List<String> METHODS = List.of("PUT", "PATCH", "DELETE");
    private static final List<String> PATHS = List.of("/api/v1/runs", "/actuator/health");
    private static final int READ_TIMEOUT_MILLIS = 4_000;

    abstract int port();

    private static byte[] form(int bytes) {
        return ("a=" + "b".repeat(bytes - 2)).getBytes(StandardCharsets.US_ASCII);
    }

    /** What the client sends: how the length is declared and how much of the body it ever sends. */
    private enum Shape {
        /** Content-Length equal to the body, body sent. */
        DECLARED,
        /** Transfer-Encoding: chunked, body and the final empty chunk sent. */
        CHUNKED,
        /** Chunked, the body over the limit sent, but the final chunk never comes. */
        CHUNKED_NEVER_ENDS,
        /** Content-Length of 100 MB and no body byte at all. */
        HUGE_LENGTH_NO_BODY
    }

    private int status(String method, String path, byte[] body, Shape shape) throws IOException {
        try (Socket socket = new Socket("localhost", port())) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            String length = shape == Shape.HUGE_LENGTH_NO_BODY
                    ? "Content-Length: 100000000"
                    : shape == Shape.DECLARED ? "Content-Length: " + body.length : "Transfer-Encoding: chunked";
            String head = method + " " + path + " HTTP/1.1\r\nHost: localhost:" + port()
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nConnection: close\r\n" + length
                    + "\r\n\r\n";
            try {
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (shape == Shape.DECLARED) {
                    out.write(body);
                } else if (shape == Shape.CHUNKED || shape == Shape.CHUNKED_NEVER_ENDS) {
                    for (int from = 0; from < body.length; from += 4_096) {
                        int size = Math.min(4_096, body.length - from);
                        out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body, from, size);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    if (shape == Shape.CHUNKED) {
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                }
                out.flush();
            } catch (IOException serverAlreadyAnswered) {
                // The server refused and closed while we were still sending: read what it said.
            }
            try {
                String line = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        .readLine();
                return line == null ? -1 : Integer.parseInt(line.split(" ")[1]);
            } catch (IOException noAnswer) {
                return -1; // timed out (the server was still waiting for the body) or reset before answering
            }
        }
    }

    private void assertAllRefused(Shape shape, int bytes) throws IOException {
        for (String method : METHODS) {
            for (String path : PATHS) {
                assertThat(status(method, path, form(bytes), shape))
                        .as(
                                "%s %s, %s, %d bytes (-1: no answer within %d ms)",
                                method, path, shape, bytes, READ_TIMEOUT_MILLIS)
                        .isEqualTo(413);
            }
        }
    }

    @Test
    @DisplayName("REQ-API-009 a PUT, PATCH or DELETE form body over 16 KB with a Content-Length is 413")
    void declaredFormBodyOverTheLimitIsRefused() throws Exception {
        assertAllRefused(Shape.DECLARED, LIMIT + 1);
        assertAllRefused(Shape.DECLARED, 60_000);
    }

    @Test
    @DisplayName("REQ-API-009 a chunked PUT, PATCH or DELETE form body over 16 KB is 413")
    void chunkedFormBodyOverTheLimitIsRefused() throws Exception {
        assertAllRefused(Shape.CHUNKED, LIMIT + 1);
        assertAllRefused(Shape.CHUNKED, 60_000);
    }

    @Test
    @DisplayName("REQ-API-009 a chunked form body is refused as soon as it passes 16 KB, without waiting for its end")
    void chunkedFormBodyIsRefusedWithoutBufferingIt() throws Exception {
        assertAllRefused(Shape.CHUNKED_NEVER_ENDS, 20_000);
    }

    @Test
    @DisplayName("REQ-API-009 a declared 100 MB form body is refused from its header alone, without reading it")
    void declaredHugeFormBodyIsRefusedFromTheHeader() throws Exception {
        for (String method : METHODS) {
            for (String path : PATHS) {
                assertThat(status(method, path, new byte[0], Shape.HUGE_LENGTH_NO_BODY))
                        .as("%s %s (-1: no answer within %d ms)", method, path, READ_TIMEOUT_MILLIS)
                        .isEqualTo(413);
            }
        }
    }

    @Test
    @DisplayName("REQ-API-009 a form body of exactly 16 KB is not refused as too large")
    void formBodyAtTheLimitIsNotTooLarge() throws Exception {
        for (String method : METHODS) {
            for (String path : PATHS) {
                assertThat(status(method, path, form(LIMIT), Shape.DECLARED))
                        .as("%s %s", method, path)
                        .isBetween(400, 499)
                        .isNotEqualTo(413);
            }
        }
    }

    @Test
    @DisplayName("REQ-API-009 a PUT form body that is not valid percent-encoding is a client error, not a 500")
    void malformedFormBodyIsNotAServerError() throws Exception {
        byte[] malformed = "a=%zz".getBytes(StandardCharsets.US_ASCII);

        for (String path : PATHS) {
            assertThat(status("PUT", path, malformed, Shape.DECLARED))
                    .as("PUT %s", path)
                    .isBetween(400, 499);
            assertThat(status("PUT", path, malformed, Shape.CHUNKED))
                    .as("chunked PUT %s", path)
                    .isBetween(400, 499);
        }
    }
}
