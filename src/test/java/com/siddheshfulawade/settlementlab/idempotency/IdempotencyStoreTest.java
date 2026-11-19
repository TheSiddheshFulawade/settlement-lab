package com.siddheshfulawade.settlementlab.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

    private static final TransferId ID = TransferId.of("t-1");

    private final IdempotencyStore store = new IdempotencyStore();

    @Test
    @DisplayName("REQ-LAB-008 the first claim owns the id; a later claim is a duplicate")
    void firstClaimOwns() {
        IdempotencyStore.Claim first = store.claim(ID);
        IdempotencyStore.Claim second = store.claim(ID);

        assertThat(first.owner()).isTrue();
        assertThat(second.owner()).isFalse();
        assertThat(store.duplicates()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-LAB-008 a duplicate waiting on another thread sees the owner's outcome")
    void duplicateSeesOwnersOutcome() {
        IdempotencyStore.Claim owner = store.claim(ID);
        IdempotencyStore.Claim duplicate = store.claim(ID);
        assertThat(duplicate.outcome()).isNotDone();

        Thread.ofVirtual().start(() -> owner.outcome().complete(Outcome.REJECTED_FUNDS));

        await().atMost(Duration.ofSeconds(5)).until(() -> duplicate.outcome().isDone());
        assertThat(duplicate.outcome().join()).isEqualTo(Outcome.REJECTED_FUNDS);
    }

    @Test
    @DisplayName("REQ-LAB-008 a duplicate cannot change the outcome other callers see")
    void duplicateCannotChangeOutcome() {
        IdempotencyStore.Claim owner = store.claim(ID);
        IdempotencyStore.Claim duplicate = store.claim(ID);

        duplicate.outcome().complete(Outcome.QUEUE_FULL);
        owner.outcome().complete(Outcome.POSTED);

        assertThat(store.claim(ID).outcome().join()).isEqualTo(Outcome.POSTED);
    }

    @Test
    @DisplayName("REQ-LAB-008 execute runs the work once and returns the first outcome to every caller")
    void executeRunsWorkOnce() {
        AtomicInteger runs = new AtomicInteger();

        CompletableFuture<Outcome> first = store.execute(ID, () -> {
            runs.incrementAndGet();
            return Outcome.POSTED;
        });
        CompletableFuture<Outcome> second = store.execute(ID, () -> {
            runs.incrementAndGet();
            return Outcome.REJECTED_FUNDS;
        });

        assertThat(runs).hasValue(1);
        assertThat(first.join()).isEqualTo(Outcome.POSTED);
        assertThat(second.join()).isEqualTo(Outcome.POSTED);
        assertThat(store.duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-LAB-009 a released claim (never processed, e.g. QUEUE_FULL) can be claimed again")
    void releasedClaimCanBeClaimedAgain() {
        IdempotencyStore.Claim owner = store.claim(ID);
        owner.outcome().complete(Outcome.QUEUE_FULL);

        assertThat(store.release(ID, owner)).isTrue();

        assertThat(store.size()).isZero();
        assertThat(store.claim(ID).owner()).isTrue();
    }

    @Test
    @DisplayName("only the owner's claim can release an id")
    void onlyOwnerCanRelease() {
        IdempotencyStore.Claim owner = store.claim(ID);
        IdempotencyStore.Claim duplicate = store.claim(ID);

        assertThat(store.release(ID, duplicate)).isFalse();
        assertThat(store.release(TransferId.of("other"), owner)).isFalse();
        assertThat(store.size()).isEqualTo(1);
    }

    // TODO-ME: write `failedWorkIsReleasedAndCanRunAgain`: when the work passed to execute throws a
    //  RuntimeException, (1) the returned future completes exceptionally with that same exception,
    //  and (2) a later execute with the same id runs its work and returns its outcome.
    //  Check it: the test passes; then delete the `outcomes.remove(id, claim.outcome())` line in
    //  IdempotencyStore.execute, run `./mvnw test -Dtest=IdempotencyStoreTest` and see part (2) fail.
}
