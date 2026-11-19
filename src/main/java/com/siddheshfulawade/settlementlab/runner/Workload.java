package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The same transfers for every cell of the matrix, generated from a seed (REQ-LAB-015). Transfer
 * {@code i} depends only on the seed and {@code i}, so any thread can make any transfer without
 * holding a million of them in memory.
 *
 * @param hotAccounts accounts 1..hotAccounts are "hot"
 * @param hotPercent share of transfers that stay within the hot accounts (the contention knob)
 * @param duplicatePercent share of transfers that resend an earlier transfer unchanged
 */
public record Workload(int accounts, int transfers, int hotAccounts, int hotPercent, int duplicatePercent, long seed) {

    public static final long OPENING_BALANCE = 10_000;
    private static final long MAX_AMOUNT = 1_000;

    public Workload {
        if (accounts < 2 || hotAccounts < 2 || hotAccounts > accounts || transfers < 1) {
            throw new IllegalArgumentException("need at least 2 accounts, 2 hot accounts and 1 transfer");
        }
        if (hotPercent < 0 || hotPercent > 100 || duplicatePercent < 0 || duplicatePercent > 100) {
            throw new IllegalArgumentException("percentages must be 0 to 100");
        }
    }

    public Map<AccountId, Money> openingBalances() {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= accounts; id++) {
            opening.put(AccountId.of(id), Money.of(OPENING_BALANCE));
        }
        return opening;
    }

    /** Transfer number {@code index}; a duplicate returns exactly the earlier transfer it repeats. */
    public Transfer transfer(int index) {
        int current = index;
        while (true) {
            SplittableRandom random = randomFor(current);
            if (current > 0 && random.nextInt(100) < duplicatePercent) {
                current = random.nextInt(current); // resend an earlier one
                continue;
            }
            int range = random.nextInt(100) < hotPercent ? hotAccounts : accounts;
            long source = random.nextLong(1, range + 1);
            long target = random.nextLong(1, range + 1);
            if (target == source) {
                target = source % range + 1; // keep it valid: never the same account
            }
            return new Transfer(
                    TransferId.of("t-" + current),
                    AccountId.of(source),
                    AccountId.of(target),
                    Money.of(random.nextLong(1, MAX_AMOUNT + 1)));
        }
    }

    private SplittableRandom randomFor(int index) {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L + index);
    }
}
