package com.siddheshfulawade.settlementlab.runner;

import java.time.Duration;

/**
 * How a supplied ledger gets its postings to the general ledger when the runner publishes nothing
 * itself (the database does it through its outbox). The runner always closes it.
 */
public interface GlDelivery extends AutoCloseable {

    /** Waits until every posting has reached the general ledger; false if the timeout passes first. */
    boolean drain(Duration timeout);

    /** The most outbox rows waiting at once during the run. */
    long outboxPeak();

    /** Stops any background delivery; never throws a checked exception. */
    @Override
    void close();
}
