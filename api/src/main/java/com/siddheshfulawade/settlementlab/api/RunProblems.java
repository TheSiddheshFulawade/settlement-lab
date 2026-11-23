package com.siddheshfulawade.settlementlab.api;

import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the runs API's failures into problem details (api-contract section 3). Messages are written here
 * from fixed text and numbers: never from an exception message, a client address or a library.
 */
@RestControllerAdvice(assignableTypes = RunController.class)
class RunProblems {

    private static final long BUSY_RETRY_SECONDS = 5;

    private final RunsProperties properties;

    RunProblems(RunsProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(InvalidRunRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidRunRequestException e) {
        return ResponseEntity.badRequest()
                .body(Problems.of(
                        HttpStatus.BAD_REQUEST, "invalid-run-request", "Invalid run request", e.getMessage()));
    }

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(
                        HttpStatus.NOT_FOUND,
                        "run-not-found",
                        "Run not found",
                        "There is no run with this id; it may have expired."));
    }

    @ExceptionHandler(RunRefusedException.class)
    ResponseEntity<ProblemDetail> refused(RunRefusedException e) {
        long seconds = e.retryAfterSeconds();
        return switch (e.limit()) {
            case PER_IP ->
                retryLater(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate-limited",
                        "Too many runs",
                        properties.perIpPerHour() + " runs per hour per visitor.",
                        seconds);
            case BUSY ->
                retryLater(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "busy",
                        "Busy",
                        properties.maxActive() + " runs are already active.",
                        seconds);
            case GLOBAL ->
                retryLater(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "busy",
                        "Busy",
                        properties.globalPerHour() + " runs per hour across all visitors.",
                        seconds);
        };
    }

    @ExceptionHandler(SubscriberLimitException.class)
    ResponseEntity<ProblemDetail> tooManyViewers() {
        return retryLater(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate-limited",
                "Too many viewers",
                "A run has at most " + properties.subscribersPerRun() + " live viewers.",
                BUSY_RETRY_SECONDS);
    }

    @ExceptionHandler(ServiceClosedException.class)
    ResponseEntity<ProblemDetail> shuttingDown() {
        return retryLater(
                HttpStatus.SERVICE_UNAVAILABLE,
                "busy",
                "Shutting down",
                "The service is stopping.",
                BUSY_RETRY_SECONDS);
    }

    private static ResponseEntity<ProblemDetail> retryLater(
            HttpStatus status, String type, String title, String reason, long seconds) {
        String detail = String.format(Locale.ROOT, "%s Try again in %,d s.", reason, seconds);
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(Problems.withRetryAfter(status, type, title, detail, seconds));
    }
}
