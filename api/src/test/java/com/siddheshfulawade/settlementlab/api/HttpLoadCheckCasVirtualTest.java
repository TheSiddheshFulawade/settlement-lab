package com.siddheshfulawade.settlementlab.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** {@link HttpLoadCheckContract} with the CAS ledger and VIRTUAL threads, over a random port. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.ledger=CAS",
            "settlement.threads=VIRTUAL",
            "settlement.faults=FLAKY",
            "settlement.accounts=100",
            "settlement.opening-balance=10000"
        })
class HttpLoadCheckCasVirtualTest extends HttpLoadCheckContract {

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
        return "VIRTUAL";
    }
}
