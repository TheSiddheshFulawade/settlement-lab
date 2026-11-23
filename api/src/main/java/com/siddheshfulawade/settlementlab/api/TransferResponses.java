package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.InvalidReason;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Outcome to HTTP status (REQ-API-001), in one place so it is easy to read and to test. */
final class TransferResponses {

    /** Seconds a client should wait after QUEUE_FULL before retrying with the same id. */
    static final String RETRY_AFTER_SECONDS = "1";

    private TransferResponses() {}

    static HttpStatus statusFor(Outcome outcome) {
        return switch (outcome) {
            case POSTED -> HttpStatus.CREATED;
            case REJECTED_FUNDS -> HttpStatus.UNPROCESSABLE_CONTENT;
            case REJECTED_INVALID -> HttpStatus.BAD_REQUEST;
            case QUEUE_FULL, REJECTED_CONTENTION -> HttpStatus.SERVICE_UNAVAILABLE;
            // The engine answers a duplicate with the original outcome, so this is not expected.
            case DUPLICATE -> HttpStatus.OK;
        };
    }

    static ResponseEntity<TransferResponse> of(String id, Outcome outcome, Optional<InvalidReason> reason) {
        String why =
                outcome == Outcome.REJECTED_INVALID ? reason.map(Enum::name).orElse(null) : null;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(statusFor(outcome));
        // Both store nothing and release the id, so the client may retry with the same id.
        if (outcome == Outcome.QUEUE_FULL || outcome == Outcome.REJECTED_CONTENTION) {
            builder.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return builder.body(new TransferResponse(id, outcome, why));
    }
}
