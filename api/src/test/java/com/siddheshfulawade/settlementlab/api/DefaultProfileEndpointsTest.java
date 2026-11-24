package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * REQ-API-010 the other way round: without the prod profile the local endpoints are all there. The Spring
 * context (so the engine) is shared with TransferControllerTest: only accounts 14 and 15 move here.
 */
@SpringBootTest(properties = {"settlement.accounts=20", "settlement.opening-balance=1000"})
@AutoConfigureMockMvc
class DefaultProfileEndpointsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("REQ-API-010 the default profile registers the local endpoints and they answer")
    void localEndpointsStay() {
        assertThat(ProdProfileTest.registeredPatterns(context))
                .contains(
                        "/api/v1/runs",
                        "/api/v1/transfers",
                        "/api/v1/accounts/{id}",
                        "/api/v1/metrics",
                        "/api/v1/events",
                        "/actuator/health");

        assertThat(mvc.get().uri("/api/v1/metrics").exchange().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.get().uri("/api/v1/accounts/1").exchange().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.post()
                        .uri("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"default-profile-1\",\"source\":14,\"target\":15,\"amount\":1}")
                        .exchange()
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
    }
}
