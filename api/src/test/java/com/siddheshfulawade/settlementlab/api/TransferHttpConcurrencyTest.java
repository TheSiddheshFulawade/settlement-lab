package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Over a real socket: the idempotency guarantee holds for HTTP clients racing each other. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"settlement.accounts=10", "settlement.opening-balance=1000"})
class TransferHttpConcurrencyTest {

    @LocalServerPort
    int port;

    @Autowired
    SettlementRuntime runtime;

    @Test
    @DisplayName("REQ-API-002 20 clients sending the same transfer at once all get 201, and money moves once")
    void concurrentDuplicatesOverHttp() throws Exception {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"same\",\"source\":1,\"target\":2,\"amount\":25}"))
                .build();
        CountDownLatch start = new CountDownLatch(1);
        Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    statuses.add(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode());
                } catch (Exception e) {
                    statuses.add(-1);
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            assertThat(thread.join(Duration.ofSeconds(20))).isTrue();
        }

        assertThat(statuses).hasSize(20).containsOnly(201);
        assertThat(runtime.engine().snapshot().get(AccountId.of(1)).minorUnits())
                .isEqualTo(975);
        assertThat(runtime.engine().postings()).hasSize(1);
    }
}
