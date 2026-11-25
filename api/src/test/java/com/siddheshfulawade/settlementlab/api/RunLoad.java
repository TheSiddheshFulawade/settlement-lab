package com.siddheshfulawade.settlementlab.api;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;

/** Starts one run over HTTP, reads its event stream to the end, then reads the run again with a plain GET. */
final class RunLoad {

    /** What the client saw. Fields after a refused start stay at their "nothing happened" values. */
    record RunObservation(
            int transfers,
            int acceptStatus,
            String acceptBody,
            String location,
            LoadHttp.Stream events,
            int getStatus,
            String getBody,
            String requestBody) {}

    /** The contract promises a report within 60 s of the 202; the extra margin is for slow machines. */
    private static final Duration EVENTS_LIMIT = Duration.ofSeconds(90);

    private RunLoad() {}

    static RunObservation run(LoadHttp http, String ledger, String threads, String faults, int transfers) {
        String requestBody = RunHttp.body(ledger, threads, faults, transfers, 2);
        LoadHttp.Response accepted = http.post("/api/v1/runs", requestBody);
        String location = accepted.headers().firstValue("Location").orElse("");
        if (accepted.status() != 202) {
            return new RunObservation(
                    transfers,
                    accepted.status(),
                    accepted.body(),
                    location,
                    new LoadHttp.Stream(0, "", false),
                    0,
                    "",
                    requestBody);
        }
        String runId = JsonPath.read(accepted.body(), "$.runId");
        LoadHttp.Stream events = http.readEvents("/api/v1/runs/" + runId + "/events", EVENTS_LIMIT);
        LoadHttp.Response after = http.get("/api/v1/runs/" + runId);
        return new RunObservation(
                transfers,
                accepted.status(),
                accepted.body(),
                location,
                events,
                after.status(),
                after.body(),
                requestBody);
    }
}
