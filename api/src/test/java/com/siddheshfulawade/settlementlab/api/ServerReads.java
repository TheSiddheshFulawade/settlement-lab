package com.siddheshfulawade.settlementlab.api;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** What the load check reads back over HTTP: every account balance, and the engine and general-ledger counters. */
final class ServerReads {

    /** Counters since the server started (the check works with differences, so a used server is fine). */
    record Metrics(
            long submitted,
            long posted,
            long rejectedFunds,
            long rejectedInvalid,
            long queueFull,
            long rejectedContention,
            long duplicates,
            long failed,
            long listenerErrors,
            long publishErrors,
            long glEntries,
            long outboxSize) {}

    private static final int MAX_ACCOUNTS = 100_000;

    private ServerReads() {}

    /** Reads accounts 1, 2, 3 ... until the first 404: the server says how many there are. */
    static Map<Long, Long> discoverBalances(LoadHttp http) {
        Map<Long, Long> balances = new TreeMap<>();
        for (long id = 1; id <= MAX_ACCOUNTS; id++) {
            LoadHttp.Response response = http.get("/api/v1/accounts/" + id);
            if (response.status() == 404) {
                break;
            }
            if (response.status() != 200) {
                throw new IllegalStateException("GET account " + id + " answered " + response.status());
            }
            balances.put(id, ((Number) JsonPath.read(response.body(), "$.balance")).longValue());
        }
        return balances;
    }

    /** Reads the given accounts with {@code clients} readers at a time; a failed read is an exception. */
    static Map<Long, Long> readBalances(LoadHttp http, List<Long> ids, int clients) {
        Map<Long, Long> balances = new ConcurrentHashMap<>();
        Queue<Long> failed = new ConcurrentLinkedQueue<>();
        AtomicInteger next = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(clients);
        for (int c = 0; c < clients; c++) {
            Thread.ofVirtual().start(() -> {
                try {
                    int i;
                    while ((i = next.getAndIncrement()) < ids.size()) {
                        long id = ids.get(i);
                        LoadHttp.Response response = http.get("/api/v1/accounts/" + id);
                        if (response.status() == 200) {
                            balances.put(id, ((Number) JsonPath.read(response.body(), "$.balance")).longValue());
                        } else {
                            failed.add(id);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading balances", e);
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("could not read balances of accounts " + failed);
        }
        return new TreeMap<>(balances);
    }

    static Metrics readMetrics(LoadHttp http) {
        LoadHttp.Response response = http.get("/api/v1/metrics");
        if (response.status() != 200) {
            throw new IllegalStateException("GET /api/v1/metrics answered " + response.status());
        }
        Object doc = JsonPath.parse(response.body()).read("$");
        return new Metrics(
                num(doc, "$.engine.submitted"),
                num(doc, "$.engine.posted"),
                num(doc, "$.engine.rejectedFunds"),
                num(doc, "$.engine.rejectedInvalid"),
                num(doc, "$.engine.queueFull"),
                num(doc, "$.engine.rejectedContention"),
                num(doc, "$.engine.duplicates"),
                num(doc, "$.engine.failed"),
                num(doc, "$.engine.listenerErrors"),
                num(doc, "$.engine.publishErrors"),
                num(doc, "$.generalLedger.entries"),
                num(doc, "$.outbox.size"));
    }

    private static long num(Object doc, String path) {
        return ((Number) JsonPath.read(doc, path)).longValue();
    }
}
