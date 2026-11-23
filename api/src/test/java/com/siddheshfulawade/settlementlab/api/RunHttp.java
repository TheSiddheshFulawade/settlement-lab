package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared helpers for the runs API tests that talk to a real port, plus the contract's field lists. */
final class RunHttp {

    static final String PROBLEM_BASE = "https://api.siddheshfulawade.com/problems/";

    /** Long when the context paces slowly (settlement.runs.transfers-per-second); fast otherwise. */
    static final String SLOW_RUN =
            "{\"ledger\":\"LOCKING\",\"threads\":\"VIRTUAL\",\"faults\":\"FLAKY\",\"transfers\":20000,\"duplicatePercent\":2}";

    static final String FLAKY_RUN =
            "{\"ledger\":\"CAS\",\"threads\":\"VIRTUAL\",\"faults\":\"FLAKY\",\"transfers\":3000,\"duplicatePercent\":2}";

    static final String QUICK_RUN =
            "{\"ledger\":\"CAS\",\"threads\":\"PLATFORM\",\"faults\":\"NONE\",\"transfers\":50,\"duplicatePercent\":0}";

    static final Set<String> REPORT_KEYS = Set.of(
            "runId",
            "status",
            "stoppedEarly",
            "settings",
            "durationMs",
            "transfersCompleted",
            "openingTotal",
            "closingTotal",
            "duplicatesSent",
            "duplicatesApplied",
            "throughputPerSecond",
            "p50Micros",
            "p99Micros",
            "glRetries",
            "breakerOpens",
            "invariants",
            "allPassed");

    static final Set<String> METRICS_KEYS = Set.of(
            "elapsedMs",
            "submitted",
            "posted",
            "rejected",
            "duplicates",
            "throughputPerSecond",
            "p99Micros",
            "queueDepth",
            "queueCapacity",
            "glRetries",
            "breakerState",
            "outboxSize");

    static final List<String> INVARIANT_NAMES = List.of(
            "TOTAL_CONSERVED",
            "NO_NEGATIVE_BALANCE",
            "POSTED_AT_MOST_ONCE",
            "POSTINGS_EXPLAIN_BALANCES",
            "POSTINGS_MATCH_ACCEPTED",
            "GL_MATCHES_POSTINGS");

    private static final AtomicInteger IPS = new AtomicInteger();

    private RunHttp() {}

    /** A fresh client address per call, so tests never share a per-IP window. */
    static String nextIp() {
        int n = IPS.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    static String body(String ledger, String threads, String faults, int transfers, int duplicatePercent) {
        return "{\"ledger\":\"%s\",\"threads\":\"%s\",\"faults\":\"%s\",\"transfers\":%d,\"duplicatePercent\":%d}"
                .formatted(ledger, threads, faults, transfers, duplicatePercent);
    }

    static HttpResponse<String> post(int port, String json, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    static String runIdOf(HttpResponse<String> accepted) {
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        String location = accepted.headers().firstValue("Location").orElseThrow();
        assertThat(location).matches("/api/v1/runs/[A-Za-z0-9-]{1,64}");
        return location.substring("/api/v1/runs/".length());
    }

    static Map<String, Object> json(String body) {
        return JsonPath.read(body, "$");
    }

    static long number(Map<String, Object> json, String key) {
        return ((Number) json.get(key)).longValue();
    }

    /** Every field of the contract's report, the six invariants by name in order, all passed. */
    @SuppressWarnings("unchecked")
    static void assertContractReport(Map<String, Object> report, String runId) {
        assertThat(report.keySet()).as("report fields").isEqualTo(REPORT_KEYS);
        assertThat(report.get("runId")).isEqualTo(runId);
        assertThat(report.get("status")).isEqualTo("DONE");
        assertThat(report.get("stoppedEarly")).isInstanceOf(Boolean.class);
        assertThat(((Map<String, Object>) report.get("settings")).keySet())
                .containsExactlyInAnyOrder("ledger", "threads", "faults", "transfers", "duplicatePercent");
        List<Map<String, Object>> invariants = (List<Map<String, Object>>) report.get("invariants");
        assertThat(invariants).extracting(i -> i.get("name")).containsExactlyElementsOf(INVARIANT_NAMES);
        assertThat(invariants).allSatisfy(i -> {
            assertThat(i.keySet()).containsExactlyInAnyOrder("name", "passed", "counterexample");
            assertThat(i.get("passed")).isEqualTo(true);
            assertThat(i.get("counterexample")).isNull();
        });
        assertThat(report.get("allPassed")).isEqualTo(true);
        assertThat(report.get("runId").toString()).matches("[A-Za-z0-9-]{1,64}");
        assertThat(report.get("openingTotal")).as("plain integer total").isInstanceOfAny(Integer.class, Long.class);
        assertThat(report.get("closingTotal")).as("plain integer total").isInstanceOfAny(Integer.class, Long.class);
        assertThat(number(report, "openingTotal")).isEqualTo(1_000 * 10_000L);
        assertThat(number(report, "closingTotal")).isEqualTo(number(report, "openingTotal"));
        assertThat(number(report, "duplicatesApplied")).isZero();
    }
}
