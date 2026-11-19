package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/accounts/{id}} (REQ-API-004): one balance, read without a full snapshot. */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    /** @param balance minor units */
    record AccountResponse(long id, long balance) {}

    private final SettlementRuntime runtime;

    AccountController(SettlementRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/{id}")
    ResponseEntity<?> balance(@PathVariable long id) {
        return runtime.engine()
                .balance(AccountId.of(id))
                .<ResponseEntity<?>>map(money -> ResponseEntity.ok(new AccountResponse(id, money.minorUnits())))
                .orElseGet(() -> {
                    ProblemDetail problem =
                            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No account with id " + id);
                    problem.setTitle("Account not found");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
                });
    }
}
