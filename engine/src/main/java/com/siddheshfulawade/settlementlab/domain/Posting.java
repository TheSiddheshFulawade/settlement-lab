package com.siddheshfulawade.settlementlab.domain;

/**
 * The ledger's record that a transfer was applied. {@code sequence} starts at 1 and is unique per
 * ledger. Postings are the source of truth the general ledger must match (SPEC.md section 4.4).
 */
public record Posting(long sequence, TransferId transferId, AccountId source, AccountId target, Money amount) {}
