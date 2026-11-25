package com.siddheshfulawade.settlementlab.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the transfer load saw, from the client's side only: what it sent, every answer, the
 * balances and counters before and after. The checks in {@link TransferChecks} read nothing else.
 */
record TransferObservation(
        List<TransferPlan.Op> ops,
        List<TransferLoad.Answer> answers,
        Map<Long, Long> openingBalances,
        Map<Long, Long> closingBalances,
        ServerReads.Metrics before,
        ServerReads.Metrics after) {

    /** The first decided answer of each transfer id: the "original" outcome. Ids never decided are absent. */
    Map<String, TransferLoad.Answer> originals() {
        Map<String, TransferLoad.Answer> originals = new LinkedHashMap<>();
        for (TransferLoad.Answer answer : answers) {
            if (answer.decided()) {
                originals.putIfAbsent(answer.id(), answer);
            }
        }
        return originals;
    }

    Map<String, TransferPlan.Op> opsById() {
        Map<String, TransferPlan.Op> byId = new HashMap<>();
        ops.forEach(op -> byId.put(op.id(), op));
        return byId;
    }

    /** The ids the client saw answered 201: the transfers it expects to be posted, once each. */
    List<String> postedIds() {
        return originals().values().stream()
                .filter(a -> a.status() == 201)
                .map(TransferLoad.Answer::id)
                .toList();
    }

    long decidedWith(String outcome) {
        return originals().values().stream()
                .filter(a -> outcome.equals(a.outcome()))
                .count();
    }
}
