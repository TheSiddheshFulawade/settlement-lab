package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.InvalidReason;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/transfers} (REQ-API-001 to 003). Returns a future, so no request thread
 * waits for the engine; Spring completes the response when the outcome arrives.
 */
@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

    private final SettlementRuntime runtime;

    TransferController(SettlementRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<TransferResponse>> submit(@Valid @RequestBody TransferRequest request) {
        Transfer transfer = request.toTransfer();
        Optional<InvalidReason> reason = runtime.engine().validate(transfer);
        return runtime.engine()
                .submit(transfer)
                .toCompletableFuture()
                .thenApply(outcome -> TransferResponses.of(request.id(), outcome, reason));
    }

    /** The engine is closed: the application is shutting down. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail shuttingDown(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Shutting down");
        return problem;
    }
}
