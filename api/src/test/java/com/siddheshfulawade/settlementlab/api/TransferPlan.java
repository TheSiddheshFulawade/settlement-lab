package com.siddheshfulawade.settlementlab.api;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The seeded mix of transfers the load sends. Every id matches REQ-API-009's pattern. The mix is a
 * function of the seed alone, so a failure can be replayed.
 */
final class TransferPlan {

    enum Kind {
        /** Small amount between two known accounts: POSTED, or REJECTED_FUNDS if the source ran dry. */
        NORMAL,
        /** More than all the money in the system: always REJECTED_FUNDS (422). */
        OVERDRAW,
        /** Zero or negative amount, same account, or an unknown account: always REJECTED_INVALID (400). */
        INVALID
    }

    /** {@code copies} is how many clients send this transfer at the same moment. */
    record Op(String id, long source, long target, long amount, Kind kind, int copies) {
        String json() {
            return "{\"id\":\"%s\",\"source\":%d,\"target\":%d,\"amount\":%d}".formatted(id, source, target, amount);
        }
    }

    private TransferPlan() {}

    /**
     * @param accountIds the accounts that exist (read from the server)
     * @param totalMoney the sum of all opening balances (read from the server)
     * @param tag short text in every id, so a second run against the same server never reuses an id
     */
    static List<Op> build(List<Long> accountIds, long totalMoney, long seed, int count, String tag) {
        SplittableRandom random = new SplittableRandom(seed);
        long unknownAccount =
                accountIds.stream().mapToLong(Long::longValue).max().orElse(0) + 1_000;
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "lc-" + tag + "-" + i;
            long source = pick(accountIds, random);
            long target = pickOther(accountIds, random, source);
            int copies = random.nextInt(100) < 20 ? 2 + random.nextInt(2) : 1;
            int roll = random.nextInt(100);
            if (roll < 8) {
                ops.add(new Op(id, source, target, totalMoney + 1, Kind.OVERDRAW, copies));
            } else if (roll < 15) {
                ops.add(invalid(id, source, target, unknownAccount, random.nextInt(4), copies));
            } else {
                ops.add(new Op(id, source, target, 1 + random.nextInt(500), Kind.NORMAL, copies));
            }
        }
        return ops;
    }

    private static Op invalid(String id, long source, long target, long unknownAccount, int flavour, int copies) {
        return switch (flavour) {
            case 0 -> new Op(id, source, target, 0, Kind.INVALID, copies);
            case 1 -> new Op(id, source, target, -7, Kind.INVALID, copies);
            case 2 -> new Op(id, source, source, 5, Kind.INVALID, copies);
            default -> new Op(id, unknownAccount, target, 5, Kind.INVALID, copies);
        };
    }

    private static long pick(List<Long> ids, SplittableRandom random) {
        return ids.get(random.nextInt(ids.size()));
    }

    private static long pickOther(List<Long> ids, SplittableRandom random, long not) {
        long other;
        do {
            other = pick(ids, random);
        } while (other == not);
        return other;
    }
}
