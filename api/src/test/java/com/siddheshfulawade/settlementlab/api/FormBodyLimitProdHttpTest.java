package com.siddheshfulawade.settlementlab.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** {@link FormBodyLimitContract} with the {@code prod} profile (the public service). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=prod")
class FormBodyLimitProdHttpTest extends FormBodyLimitContract {

    @LocalServerPort
    int port;

    @Override
    int port() {
        return port;
    }
}
