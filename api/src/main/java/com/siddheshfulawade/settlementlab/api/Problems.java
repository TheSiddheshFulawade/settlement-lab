package com.siddheshfulawade.settlementlab.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 9457 problem details of the runs API: type URLs are part of the published contract. */
final class Problems {

    static final String TYPE_BASE = "https://api.siddheshfulawade.com/problems/";

    private Problems() {}

    static ProblemDetail of(HttpStatus status, String typeSuffix, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + typeSuffix));
        problem.setTitle(title);
        return problem;
    }

    /** A 429 or 503: {@code retryAfterSeconds} in the body always equals the {@code Retry-After} header. */
    static ProblemDetail withRetryAfter(
            HttpStatus status, String typeSuffix, String title, String detail, long retryAfterSeconds) {
        ProblemDetail problem = of(status, typeSuffix, title, detail);
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem;
    }
}
