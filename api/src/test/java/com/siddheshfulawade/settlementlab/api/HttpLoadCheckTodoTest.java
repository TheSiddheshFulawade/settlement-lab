package com.siddheshfulawade.settlementlab.api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The TODO-ME of V2-05, on its own server (its own settings, so its own Spring context). Whatever the
 * test you write does to this server, it cannot disturb the other HTTP load tests, whose contract checks
 * the engine's invariants over everything their server holds.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "settlement.ledger=LOCKING",
            "settlement.threads=VIRTUAL",
            "settlement.faults=NONE",
            "settlement.accounts=10",
            "settlement.opening-balance=5000"
        })
class HttpLoadCheckTodoTest {

    @LocalServerPort
    int port;

    @Test
    @Disabled("TODO-ME: write this test, then remove @Disabled")
    @DisplayName("REQ-API-002 the same id sent again with a different amount still gets the original answer")
    void sameIdWithDifferentAmount() {
        // TODO-ME: with LoadHttp (new LoadHttp("http://localhost:" + port)), POST /api/v1/transfers twice with
        // the same id ("todo-me-1") from account 1 to 2, amounts 10 and 99. Assert both answers are 201 POSTED,
        // and that GET /api/v1/accounts/1 dropped by 10 only (read it before and after). This server is only
        // yours, so nothing else has touched it. Check: remove @Disabled and run
        // mvnw.cmd -q -pl api -am test -Dtest=HttpLoadCheckTodoTest -Dsurefire.failIfNoSpecifiedTests=false
        // Then break it on purpose: ask yourself which line in the engine would make the 99 apply, and why the
        // test would catch it.
    }
}
