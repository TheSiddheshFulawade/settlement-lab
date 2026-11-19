package com.siddheshfulawade.settlementlab.domain;

/**
 * Where the engine sends each posting after the ledger recorded it (REQ-LAB-011). A port in domain
 * terms: the engine knows only this interface; the general-ledger adapter (outbox, retries) lives in
 * the {@code gl} package. Implementations must not throw and must return quickly: failures are
 * theirs to keep and retry.
 */
@FunctionalInterface
public interface PostingPublisher {

    PostingPublisher NONE = posting -> {};

    void publish(Posting posting);
}
