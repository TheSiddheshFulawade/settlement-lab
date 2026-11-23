package com.siddheshfulawade.settlementlab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small builders shared by the domain tests. */
public final class Fixtures {

    public static final AccountId A = AccountId.of(1);
    public static final AccountId B = AccountId.of(2);
    public static final AccountId C = AccountId.of(3);

    private Fixtures() {}

    /** Accounts 1..count, each opening with the same balance. */
    public static Map<AccountId, Money> uniformAccounts(int count, long balance) {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= count; id++) {
            opening.put(AccountId.of(id), Money.of(balance));
        }
        return opening;
    }

    public static Transfer transfer(String id, AccountId source, AccountId target, long amount) {
        return new Transfer(TransferId.of(id), source, target, Money.of(amount));
    }

    public static long total(Map<AccountId, Money> balances) {
        return balances.values().stream().mapToLong(Money::minorUnits).sum();
    }
}
