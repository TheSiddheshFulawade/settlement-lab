package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** REQ-API-009 on /transfers: a bad id or an unknown field is a 400 problem and submits nothing. */
@SpringBootTest(properties = {"settlement.accounts=20", "settlement.opening-balance=1000"})
@AutoConfigureMockMvc
class TransferInputTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    SettlementRuntime runtime;

    private MockHttpServletResponse post(String json) {
        return mvc.post()
                .uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange()
                .getResponse();
    }

    private static String transfer(String escapedId) {
        return "{\"id\":\"" + escapedId + "\",\"source\":1,\"target\":2,\"amount\":1}";
    }

    private void assertRejectedAndNothingSubmitted(String json) throws Exception {
        long submitted = runtime.engine().metrics().submitted();
        long balance = runtime.engine().snapshot().get(AccountId.of(1)).minorUnits();

        MockHttpServletResponse response = post(json);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("Exception", "jackson", "java.");
        assertThat(runtime.engine().metrics().submitted())
                .as("nothing was submitted")
                .isEqualTo(submitted);
        assertThat(runtime.engine().snapshot().get(AccountId.of(1)).minorUnits())
                .isEqualTo(balance);
    }

    @ParameterizedTest(name = "[{index}] id \"{0}\"")
    @ValueSource(
            strings = {
                "",
                " ",
                "a b",
                "a/b",
                "a\\nb",
                "a\\rb",
                "a\\r\\nb",
                "bad\\nevent: forged",
                "\\u00e9",
                "a:b",
                "a,b",
                "<b>",
                "../x",
                "a\\u0000b",
                "a\\u2028b",
                "\\u0663",
                "a\\\\b",
                "a\\\"b"
            })
    @DisplayName("REQ-API-009 a transfer id outside [A-Za-z0-9._-]{1,64} is 400 and submits nothing")
    void badIdIsRejected(String escapedId) throws Exception {
        assertRejectedAndNothingSubmitted(transfer(escapedId));
    }

    @Test
    @DisplayName("REQ-API-009 an id of 65 characters is 400; of 64 characters it is accepted")
    void idLengthBoundary() throws Exception {
        assertRejectedAndNothingSubmitted(transfer("a".repeat(65)));

        MockHttpServletResponse response = post(transfer("b".repeat(64)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    }

    @Test
    @DisplayName("REQ-API-009 every allowed id character is accepted")
    void allowedCharactersAreAccepted() throws Exception {
        for (String id : new String[] {"A.b_c-9", ".", "-", "_", "0", "z"}) {
            MockHttpServletResponse response = post(transfer("ok-chars-" + id + "-" + System.nanoTime()));

            assertThat(response.getStatus())
                    .as(id + ": " + response.getContentAsString())
                    .isEqualTo(201);
        }
        assertThat(post(transfer(".")).getStatus())
                .as("a single dot is a legal id")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("REQ-API-009 an id that is not a string is 400 and submits nothing")
    void nonStringIdIsRejected() throws Exception {
        assertRejectedAndNothingSubmitted("{\"id\":5,\"source\":1,\"target\":2,\"amount\":1}");
        assertRejectedAndNothingSubmitted("{\"id\":null,\"source\":1,\"target\":2,\"amount\":1}");
        assertRejectedAndNothingSubmitted("{\"source\":1,\"target\":2,\"amount\":1}");
    }

    @Test
    @DisplayName("REQ-API-009 an unknown field is 400 and submits nothing")
    void unknownFieldIsRejected() throws Exception {
        assertRejectedAndNothingSubmitted(
                "{\"id\":\"unknown-field-1\",\"source\":1,\"target\":2,\"amount\":1,\"extra\":true}");
        assertRejectedAndNothingSubmitted(
                "{\"id\":\"unknown-field-2\",\"source\":1,\"target\":2,\"amount\":1,\"Amount\":9}");
    }
}
