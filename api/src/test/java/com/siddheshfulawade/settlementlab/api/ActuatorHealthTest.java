package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * REQ-RUN-013: of the actuator only {@code /actuator/health} is exposed, as exactly {"status":"UP"}, with
 * no details. Run with the default profile and the shipped configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("REQ-RUN-013 GET /actuator/health is exactly {\"status\":\"UP\"}: no details, no components")
    void healthIsExactlyUp() throws Exception {
        MockHttpServletResponse response =
                mvc.get().uri("/actuator/health").exchange().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).containsIgnoringCase("json");
        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).isEqualTo(Map.of("status", "UP"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "/actuator",
                "/actuator/env",
                "/actuator/info",
                "/actuator/beans",
                "/actuator/metrics",
                "/actuator/mappings",
                "/actuator/loggers",
                "/actuator/configprops",
                "/actuator/threaddump",
                "/actuator/heapdump",
                "/actuator/conditions",
                "/actuator/health/db",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/health/diskSpace"
            })
    @DisplayName("REQ-RUN-013 every other actuator path answers 404")
    void otherActuatorPathsAreNotThere(String path) {
        MockHttpServletResponse response = mvc.get().uri(path).exchange().getResponse();

        assertThat(response.getStatus()).as(path).isEqualTo(404);
    }

    @Test
    @DisplayName("REQ-RUN-013 the actuator shutdown endpoint is not there either")
    void shutdownIsNotThere() {
        MockHttpServletResponse response =
                mvc.post().uri("/actuator/shutdown").exchange().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
    }
}
