package com.siddheshfulawade.settlementlab.pipeline;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test ledger that holds every {@code apply} at a gate until the test opens it, and records how many
 * applies run at once and on which kind of thread. Lets tests fill the queue and measure the
 * concurrency cap deterministically, without sleeping.
 */
final class GatedLedger implements Ledger {

    private final Ledger delegate;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger maxRunning = new AtomicInteger();
    private final AtomicInteger entered = new AtomicInteger();
    private final AtomicBoolean sawPlatformThread = new AtomicBoolean();
    private final AtomicBoolean sawVirtualThread = new AtomicBoolean();

    GatedLedger(Ledger delegate) {
        this.delegate = delegate;
    }

    void open() {
        gate.countDown();
    }

    int entered() {
        return entered.get();
    }

    int maxRunning() {
        return maxRunning.get();
    }

    boolean sawPlatformThread() {
        return sawPlatformThread.get();
    }

    boolean sawVirtualThread() {
        return sawVirtualThread.get();
    }

    @Override
    public Outcome apply(Transfer transfer) {
        (Thread.currentThread().isVirtual() ? sawVirtualThread : sawPlatformThread).set(true);
        maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
        entered.incrementAndGet();
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test never opened the gate");
            }
            return delegate.apply(transfer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            running.decrementAndGet();
        }
    }

    @Override
    public boolean hasAccount(AccountId id) {
        return delegate.hasAccount(id);
    }

    @Override
    public Map<AccountId, Money> snapshot() {
        return delegate.snapshot();
    }

    @Override
    public List<Posting> postings() {
        return delegate.postings();
    }
}
