package com.siddheshfulawade.settlementlab.pipeline;

/** How the worker pool runs transfers (REQ-LAB-010). Both modes cap concurrency at the same N. */
public enum ThreadMode {
    /** A fixed pool of N platform threads, each taking jobs from the queue. */
    PLATFORM,
    /** A new virtual thread per job, with a {@code Semaphore(N)} so at most N run at once. */
    VIRTUAL
}
