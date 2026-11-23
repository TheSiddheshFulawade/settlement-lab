package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.InvalidReason;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransferResponsesTest {

    @Test
    @DisplayName("REQ-API-001 each outcome has its status")
    void statusPerOutcome() {
        assertThat(TransferResponses.statusFor(Outcome.POSTED)).isEqualTo(HttpStatus.CREATED);
        assertThat(TransferResponses.statusFor(Outcome.REJECTED_FUNDS)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(TransferResponses.statusFor(Outcome.REJECTED_INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(TransferResponses.statusFor(Outcome.QUEUE_FULL)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(TransferResponses.statusFor(Outcome.REJECTED_CONTENTION)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(TransferResponses.statusFor(Outcome.DUPLICATE)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("REQ-API-001 QUEUE_FULL tells the client when to retry")
    void queueFullHasRetryAfter() {
        ResponseEntity<TransferResponse> response = TransferResponses.of("q-1", Outcome.QUEUE_FULL, Optional.empty());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).isEqualTo(new TransferResponse("q-1", Outcome.QUEUE_FULL, null));
    }

    @Test
    @DisplayName("REQ-API-008 REJECTED_CONTENTION is 503 with Retry-After, like QUEUE_FULL")
    void contentionHasRetryAfter() {
        ResponseEntity<TransferResponse> response =
                TransferResponses.of("c-1", Outcome.REJECTED_CONTENTION, Optional.empty());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).isEqualTo(new TransferResponse("c-1", Outcome.REJECTED_CONTENTION, null));
    }

    @Test
    @DisplayName("the reason is only sent for REJECTED_INVALID")
    void reasonOnlyWhenInvalid() {
        assertThat(TransferResponses.of("x", Outcome.REJECTED_INVALID, Optional.of(InvalidReason.SAME_ACCOUNT))
                        .getBody()
                        .reason())
                .isEqualTo("SAME_ACCOUNT");
        assertThat(TransferResponses.of("x", Outcome.POSTED, Optional.of(InvalidReason.SAME_ACCOUNT))
                        .getBody()
                        .reason())
                .isNull();
    }
}
