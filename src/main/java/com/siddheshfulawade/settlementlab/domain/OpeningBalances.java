package com.siddheshfulawade.settlementlab.domain;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** The opening-balance rules every ledger shares. */
final class OpeningBalances {

    private OpeningBalances() {}

    /**
     * Returns the balances sorted by account id.
     *
     * @throws IllegalArgumentException if an opening balance is negative
     * @throws ArithmeticException if the opening total does not fit in a {@code long}. Because the
     *     total fits, no single balance can overflow later: a balance is never more than the total.
     */
    static SortedMap<AccountId, Long> checked(Map<AccountId, Money> openingBalances) {
        SortedMap<AccountId, Long> sorted = new TreeMap<>();
        long total = 0;
        for (Map.Entry<AccountId, Money> entry : new TreeMap<>(openingBalances).entrySet()) {
            long balance = entry.getValue().minorUnits();
            if (balance < 0) {
                throw new IllegalArgumentException(entry.getKey() + " has a negative opening balance " + balance);
            }
            total = Math.addExact(total, balance);
            sorted.put(entry.getKey(), balance);
        }
        return sorted;
    }
}
