package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The real engine behind real HTTP handling (MockMvc). Tests share one engine, so each uses its own
 * transfer ids and accounts.
 */
@SpringBootTest(properties = {"settlement.accounts=20", "settlement.opening-balance=1000"})
@AutoConfigureMockMvc
class TransferControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    SettlementRuntime runtime;

    private MockMvcTester.MockMvcRequestBuilder post(String json) {
        return mvc.post()
                .uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private long balance(long account) {
        return runtime.engine().snapshot().get(AccountId.of(account)).minorUnits();
    }

    @Test
    @DisplayName("REQ-API-001 a valid transfer is posted: 201 and the balances move")
    void postsTransfer() {
        assertThat(post("{\"id\":\"p-1\",\"source\":1,\"target\":2,\"amount\":100}"))
                .hasStatus(201)
                .bodyJson()
                .isLenientlyEqualTo("{\"id\":\"p-1\",\"outcome\":\"POSTED\"}");

        assertThat(balance(1)).isEqualTo(900);
        assertThat(balance(2)).isEqualTo(1_100);
    }

    @Test
    @DisplayName("REQ-API-001 not enough funds: 422 and nothing moves")
    void rejectsFunds() {
        assertThat(post("{\"id\":\"f-1\",\"source\":3,\"target\":4,\"amount\":5000}"))
                .hasStatus(422)
                .bodyJson()
                .isLenientlyEqualTo("{\"id\":\"f-1\",\"outcome\":\"REJECTED_FUNDS\"}");

        assertThat(balance(3)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-API-001 an invalid transfer: 400 with the reason")
    void rejectsInvalidWithReason() {
        assertThat(post("{\"id\":\"i-1\",\"source\":5,\"target\":99,\"amount\":1}"))
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("{\"outcome\":\"REJECTED_INVALID\",\"reason\":\"UNKNOWN_TARGET\"}");
        assertThat(post("{\"id\":\"i-2\",\"source\":5,\"target\":5,\"amount\":1}"))
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"SAME_ACCOUNT\"}");
        assertThat(post("{\"id\":\"i-3\",\"source\":5,\"target\":6,\"amount\":0}"))
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"NON_POSITIVE_AMOUNT\"}");
    }

    @Test
    @DisplayName("REQ-API-002 the same id again returns the original outcome and status; money moves once")
    void replayReturnsOriginal() {
        String body = "{\"id\":\"d-1\",\"source\":7,\"target\":8,\"amount\":10}";
        long duplicatesBefore = runtime.engine().metrics().duplicates();

        assertThat(post(body)).hasStatus(201);
        assertThat(post(body)).hasStatus(201).bodyJson().isLenientlyEqualTo("{\"outcome\":\"POSTED\"}");

        assertThat(balance(7)).isEqualTo(990);
        assertThat(runtime.engine().metrics().duplicates()).isEqualTo(duplicatesBefore + 1);
    }

    @Test
    @DisplayName("REQ-API-003 a missing field: 400 problem detail, nothing submitted")
    void missingFieldIsAProblem() {
        long submittedBefore = runtime.engine().metrics().submitted();

        assertThat(post("{\"id\":\"m-1\",\"source\":9,\"amount\":10}"))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("{\"status\":400,\"title\":\"Bad Request\"}");

        assertThat(runtime.engine().metrics().submitted()).isEqualTo(submittedBefore);
        assertThat(balance(9)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-API-003 malformed JSON: 400 problem detail")
    void malformedJsonIsAProblem() {
        assertThat(post("{\"id\": ")).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("REQ-API-003 a blank id: 400 problem detail")
    void blankIdIsAProblem() {
        assertThat(post("{\"id\":\" \",\"source\":10,\"target\":11,\"amount\":1}"))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(Money.of(balance(10))).isEqualTo(Money.of(1_000));
    }

    // TODO-ME: write `closedEngineAnswers503`: in its own test class (it closes the engine, so it must
    //  not share this one's context), call runtime.close() and then POST a valid transfer. Expect 503
    //  with content type application/problem+json and title "Shutting down". Check it: the test
    //  passes; then delete the @ExceptionHandler in TransferController and see it fail with 500.
}
