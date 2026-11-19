package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = {"settlement.accounts=5", "settlement.opening-balance=500"})
@AutoConfigureMockMvc
class ReadEndpointsTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("REQ-API-004 an account's balance, in minor units")
    void accountBalance() {
        assertThat(mvc.get().uri("/api/v1/accounts/3"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("{\"id\":3,\"balance\":500}");
    }

    @Test
    @DisplayName("REQ-API-004 an unknown account is 404 with a problem detail")
    void unknownAccount() {
        assertThat(mvc.get().uri("/api/v1/accounts/99"))
                .hasStatus(404)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("{\"title\":\"Account not found\",\"status\":404}");
    }

    @Test
    @DisplayName("REQ-API-003 a non-numeric account id is 400 with a problem detail")
    void nonNumericAccount() {
        assertThat(mvc.get().uri("/api/v1/accounts/abc"))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("REQ-API-004 metrics cover the engine, the outbox, the breaker and the general ledger")
    void metrics() {
        assertThat(mvc.post()
                        .uri("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"m-1\",\"source\":1,\"target\":2,\"amount\":5}"))
                .hasStatus(201);

        assertThat(mvc.get().uri("/api/v1/metrics"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.engine.posted")
                .asNumber()
                .isEqualTo(1);
        assertThat(mvc.get().uri("/api/v1/metrics"))
                .bodyJson()
                .isLenientlyEqualTo("{\"breaker\":{\"state\":\"CLOSED\"},\"settings\":{\"ledger\":\"LOCKING\"}}");
    }

    // TODO-ME: write `zeroAndNegativeIdsAreNotFound`: GET /api/v1/accounts/0 and /api/v1/accounts/-1
    //  must answer 404 problem details, not 500 and not 400 (they are numbers, just not accounts).
    //  Check it: the test passes; then make AccountController throw for id <= 0 and see it fail.
}
