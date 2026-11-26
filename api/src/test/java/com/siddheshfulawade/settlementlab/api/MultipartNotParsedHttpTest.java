package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Nothing here takes a file upload, so a multipart body must never be parsed: parsing writes each part to a
 * temporary file, on any method, past the 16 KB cap (which skips GET, HEAD and OPTIONS).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=prod")
@ExtendWith(OutputCaptureExtension.class)
class MultipartNotParsedHttpTest {

    /** NEVER: Windows cannot delete a directory Tomcat still holds when the context outlives the class. */
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path baseDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void tomcatBaseDir(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.basedir", () -> baseDir.toString());
    }

    private static List<Path> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(baseDir)) {
            return files.filter(file -> file.getFileName().toString().startsWith("upload_"))
                    .toList();
        }
    }

    /** Sends the bytes, leaves the connection open, and returns the status line (TIMEOUT if none in 4 s). */
    private String send(String head, byte[] body) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(4_000);
            OutputStream out = socket.getOutputStream();
            out.write((head + "Host: localhost:" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine();
        } catch (SocketTimeoutException stillReadingTheBody) {
            return "TIMEOUT";
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET, /actuator/health, 200",
        "GET, /api/v1/runs/a, 404",
        "GET, /api/v1/runs/a/events, 404",
        "OPTIONS, /api/v1/runs, 200"
    })
    @DisplayName("REQ-API-009 an unfinished multipart body is answered without being read and leaves no file behind")
    void multipartBodyIsNotRead(String method, String path, int status) throws Exception {
        String part = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\n" + "x".repeat(4_096);

        String answer = send(
                method + " " + path + " HTTP/1.1\r\nContent-Type: multipart/form-data; boundary=b\r\n"
                        + "Content-Length: 900000\r\n",
                part.getBytes(StandardCharsets.US_ASCII));

        assertThat(answer).as("answered without waiting for the rest").startsWith("HTTP/1.1 " + status);
        assertThat(uploadedFiles()).as("temporary files on disk").isEmpty();
    }

    @Test
    @DisplayName("REQ-API-009 a multipart type without a boundary is a client error with no stack trace")
    void multipartWithoutBoundaryIsAClientError(CapturedOutput output) throws Exception {
        String answer = send(
                "POST /api/v1/runs HTTP/1.1\r\nContent-Type: multipart/form-data\r\nContent-Length: 2\r\n",
                "{}".getBytes(StandardCharsets.US_ASCII));

        assertThat(answer).startsWith("HTTP/1.1 415");
        assertThat(output.getAll()).doesNotContain("Exception");
    }
}
