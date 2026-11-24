package com.siddheshfulawade.settlementlab.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** {@link FormBodyLimitContract} with the default profile. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FormBodyLimitHttpTest extends FormBodyLimitContract {

    @LocalServerPort
    int port;

    @Override
    int port() {
        return port;
    }
}
