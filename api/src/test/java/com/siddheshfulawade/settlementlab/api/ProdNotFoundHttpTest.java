package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * L-6 and L-10, over a real port with the {@code prod} profile: every answer for a path the public service
 * does not serve is the contract's problem detail (never Boot's error JSON or the Whitelabel page), carries the
 * CORS header for the production origin, does not echo the path, and writes no log line with the visitor's text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=prod")
@ExtendWith(OutputCaptureExtension.class)
class ProdNotFoundHttpTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> send(String method, String path, String accept, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", accept)
                .method(
                        method,
                        method.equals("POST")
                                ? HttpRequest.BodyPublishers.ofString("{}")
                                : HttpRequest.BodyPublishers.noBody());
        if (method.equals("POST")) {
            request.header("Content-Type", "application/json");
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "{0} {1} Accept: {2}")
    @CsvSource({
        "GET, /, application/json",
        "GET, /, text/html",
        "GET, /nope, application/json",
        "GET, /api/v1/runs/;nope, application/json",
        "GET, /api/v1/runs/abc;nope=1, application/json",
        "GET, /nope, text/html",
        "POST, /api/v1/transfers, application/json",
        "POST, /api/v1/transfers, text/html"
    })
    @DisplayName(
            "REQ-API-010 a path the public service does not serve is the not-found problem detail, whatever Accept says")
    void notServedPathIsAProblemDetail(String method, String path, String accept) throws Exception {
        HttpResponse<String> response = send(method, path, accept, null);

        String body = response.body();
        assertThat(response.statusCode()).as(body).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(body, "$.type")).isEqualTo(RunHttp.PROBLEM_BASE + "not-found");
        assertThat((String) JsonPath.read(body, "$.title")).isEqualTo("Not found");
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(404);
        assertThat(body).as("the path is not echoed").doesNotContain("nope").doesNotContain("transfers");
        assertThat(body).doesNotContain("Whitelabel");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/", "/nope", "/api/v1/transfers"})
    @DisplayName("REQ-RUN-008 the production origin gets its CORS header on a not-found answer, a foreign one does not")
    void notFoundCarriesCorsForTheProductionOrigin(String path) throws Exception {
        HttpResponse<String> own = send("GET", path, "application/json", RunCorsFilter.PRODUCTION_ORIGIN);
        HttpResponse<String> foreign = send("GET", path, "application/json", "https://evil.example");

        assertThat(own.statusCode()).isEqualTo(404);
        assertThat(own.headers().firstValue("Access-Control-Allow-Origin")).contains(RunCorsFilter.PRODUCTION_ORIGIN);
        assertThat(foreign.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    @DisplayName("REQ-API-010 an unknown path below the runs prefix is a 404 problem detail, not an error page")
    void unknownPathBelowRunsIsAProblemDetail() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/runs/a/b/c", "application/json", null);

        String body = response.body();
        assertThat(response.statusCode()).as(body).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(404);
        assertThat((String) JsonPath.read(body, "$.type")).startsWith(RunHttp.PROBLEM_BASE);
        assertThat(body).doesNotContain("Whitelabel").doesNotContain("a/b/c");
    }

    @Test
    @DisplayName("REQ-RUN-006 an unknown path below the runs prefix writes no log line with the visitor's text")
    void unknownPathBelowRunsIsNotLogged(CapturedOutput output) throws Exception {
        send("GET", "/api/v1/runs/a/b/c-attacker-text", "application/json", null);
        send("GET", "/api/v1/runs/x/y%0d%0aFORGED-LOG-LINE", "application/json", null);

        send("MARKMETHOD", "/api/v1/runs", "application/json", null);

        assertThat(output.getAll())
                .doesNotContain("MARKMETHOD")
                .doesNotContain("attacker-text")
                .doesNotContain("FORGED-LOG-LINE")
                .doesNotContain("No mapping for");
    }
}
