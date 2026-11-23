package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * REQ-RUN-002: every bad run request is a 400 problem detail, starts nothing and uses no rate-limit
 * slot. Jackson's defaults would accept several of these bodies (1.5 or "20" for an int, null for a
 * primitive, a number for an enum, a missing int as 0), which is what these cases are for.
 */
@SpringBootTest(
        properties = {
            "settlement.runs.transfers-per-second=1000000",
            "settlement.runs.outage-start=100ms",
            "settlement.runs.outage-length=400ms",
            "settlement.runs.max-active=10",
            "settlement.runs.per-ip-per-hour=5",
            "settlement.runs.global-per-hour=1000"
        })
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
class RunRequestHttpTest {

    private static final String VALID =
            "{\"ledger\":\"LOCKING\",\"threads\":\"VIRTUAL\",\"faults\":\"NONE\",\"transfers\":10,\"duplicatePercent\":0}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void emptyWindows() {
        clock.advance(Duration.ofHours(2));
    }

    private MockHttpServletResponse post(String body, String ip) {
        return mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content(body)
                .exchange()
                .getResponse();
    }

    private static String with(String field, String jsonValue) {
        return VALID.replaceAll("\"" + field + "\":(\"[^\"]*\"|[0-9]+)", "\"" + field + "\":" + jsonValue);
    }

    private static String without(String field) {
        return VALID.replaceAll(",?\"" + field + "\":(\"[^\"]*\"|[0-9]+)", "").replace("{,", "{");
    }

    private static void assertInvalidRunRequest(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(body, "$.type")).isEqualTo(RunHttp.PROBLEM_BASE + "invalid-run-request");
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(400);
        assertThat((String) JsonPath.read(body, "$.title")).isNotBlank();
        assertThat(body)
                .as("no library or stack trace details in the problem")
                .doesNotContain("Exception", "jackson", "java.", "com.siddheshfulawade", "\tat ");
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("missing ledger", without("ledger")),
                Arguments.of("missing threads", without("threads")),
                Arguments.of("missing faults", without("faults")),
                Arguments.of("missing transfers", without("transfers")),
                Arguments.of("missing duplicatePercent (not defaulted to 0)", without("duplicatePercent")),
                Arguments.of("null ledger", with("ledger", "null")),
                Arguments.of("null threads", with("threads", "null")),
                Arguments.of("null faults", with("faults", "null")),
                Arguments.of("null transfers", with("transfers", "null")),
                Arguments.of("null duplicatePercent (not read as 0)", with("duplicatePercent", "null")),
                Arguments.of("unknown ledger", with("ledger", "\"POSTGRES\"")),
                Arguments.of("lower-case ledger", with("ledger", "\"locking\"")),
                Arguments.of("empty ledger", with("ledger", "\"\"")),
                Arguments.of("ledger as an enum ordinal", with("ledger", "0")),
                Arguments.of("unknown threads", with("threads", "\"FIBER\"")),
                Arguments.of("unknown faults", with("faults", "\"CHAOS\"")),
                Arguments.of("an engine-only fault profile", with("faults", "\"DUPLICATE_ACKS\"")),
                Arguments.of("transfers 0", with("transfers", "0")),
                Arguments.of("transfers -1", with("transfers", "-1")),
                Arguments.of("transfers 20001", with("transfers", "20001")),
                Arguments.of("transfers beyond int", with("transfers", "99999999999")),
                Arguments.of("transfers 1.5", with("transfers", "1.5")),
                Arguments.of("transfers as a string", with("transfers", "\"20\"")),
                Arguments.of("transfers true", with("transfers", "true")),
                Arguments.of("transfers array", with("transfers", "[10]")),
                Arguments.of("transfers object", with("transfers", "{}")),
                Arguments.of("duplicatePercent -1", with("duplicatePercent", "-1")),
                Arguments.of("duplicatePercent 11", with("duplicatePercent", "11")),
                Arguments.of("duplicatePercent 100", with("duplicatePercent", "100")),
                Arguments.of("duplicatePercent 1.5", with("duplicatePercent", "1.5")),
                Arguments.of("duplicatePercent as a string", with("duplicatePercent", "\"5\"")),
                Arguments.of("ledger as a number-like string", with("ledger", "\"1\"")),
                Arguments.of("unknown field", VALID.replace("}", ",\"extra\":1}")),
                Arguments.of("seed injected", VALID.replace("}", ",\"seed\":1}")),
                Arguments.of("accounts injected", VALID.replace("}", ",\"accounts\":5}")),
                Arguments.of("workers injected", VALID.replace("}", ",\"workers\":64}")),
                Arguments.of("empty body", ""),
                Arguments.of("empty object", "{}"),
                Arguments.of("truncated JSON", "{\"ledger\":\"LOCKING\""),
                Arguments.of("trailing comma", VALID.replace("}", ",}")),
                Arguments.of("not JSON", "ledger=LOCKING"),
                Arguments.of("JSON array", "[]"),
                Arguments.of("JSON null", "null"),
                Arguments.of("JSON string", "\"LOCKING\""),
                Arguments.of("two objects", VALID + VALID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("REQ-RUN-002 an invalid run request is a 400 problem and starts nothing")
    void invalidRequestIsRejected(String label, String body) throws Exception {
        MockHttpServletResponse response = post(body, RunHttp.nextIp());

        assertInvalidRunRequest(response);
        assertThat(response.getHeader("Location")).isNull();
    }

    @Test
    @DisplayName(
            "REQ-RUN-002 invalid requests consume no rate-limit slot: five valid ones still pass, the sixth is 429")
    void invalidRequestsUseNoSlot() throws Exception {
        String ip = RunHttp.nextIp();
        List<Arguments> bad = invalidBodies().toList();
        for (Arguments each : bad) {
            assertInvalidRunRequest(post((String) each.get()[1], ip));
        }

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse accepted = post(VALID, ip);
            assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(202);
        }
        assertThat(post(VALID, ip).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("REQ-RUN-002 both ends of every range are accepted")
    void boundariesAreAccepted() throws Exception {
        MockHttpServletResponse smallest = post(RunHttp.body("LOCKING", "VIRTUAL", "NONE", 1, 0), RunHttp.nextIp());
        MockHttpServletResponse largest = post(RunHttp.body("CAS", "PLATFORM", "OUTAGE", 20_000, 10), RunHttp.nextIp());

        assertThat(smallest.getStatus()).as(smallest.getContentAsString()).isEqualTo(202);
        assertThat(largest.getStatus()).as(largest.getContentAsString()).isEqualTo(202);
    }
}
