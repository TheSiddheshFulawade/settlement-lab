package com.siddheshfulawade.settlementlab.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** {@link HttpLoadCheckContract} with the CAS ledger and PLATFORM threads, over a random port. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.ledger=CAS",
            "settlement.threads=PLATFORM",
            "settlement.faults=FLAKY",
            "settlement.accounts=100",
            "settlement.opening-balance=10000"
        })
class HttpLoadCheckCasPlatformTest extends HttpLoadCheckContract {

    @LocalServerPort
    int port;

    @Autowired
    SettlementRuntime runtime;

    @Override
    String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    SettlementRuntime runtime() {
        return runtime;
    }

    @Override
    String ledger() {
        return "CAS";
    }

    @Override
    String threads() {
        return "PLATFORM";
    }
}
