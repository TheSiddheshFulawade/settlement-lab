package com.siddheshfulawade.settlementlab.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The runs API (SPEC section 18, docs/api-contract.md): start a run, read its status or report, watch its
 * events. The order of checks on a start is validation (400), then the per-visitor limit (429), then the
 * active-run cap (503), then the global cap (503): {@link RunService#start} does the last three in that
 * order and this controller parses first, so an invalid request never uses a slot.
 */
@RestController
@RequestMapping("/api/v1/runs")
class RunController {

    /** The limits shown in the 202 are the contract's published numbers, not this instance's settings. */
    private static final RunsProperties PUBLISHED = RunsProperties.defaults();

    /** A stream ends with its run (within 60 s); this only bounds a connection that somehow never does. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(2);

    record Limits(long maxSeconds, int runsPerHourPerIp, int concurrentRuns) {}

    record Accepted(String runId, RunStatus status, String eventsUrl, Limits limits) {}

    record Status(String runId, RunStatus status) {}

    private final RunService service;
    private final ClientIp clientIp;

    RunController(RunService service, ClientIp clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Accepted> start(HttpServletRequest http) throws IOException {
        // The body is already capped at 16 KB by BodyLimitFilter; it is parsed by hand (RunRequestParser).
        RunRequest request = RunRequestParser.parse(http.getInputStream().readAllBytes());
        List<String> forwardedFor = Collections.list(http.getHeaders("X-Forwarded-For"));
        String visitor = clientIp.resolve(forwardedFor, http.getRemoteAddr()); // never logged or put in a message
        Run run = service.start(request, visitor);
        Accepted body = new Accepted(
                run.id(),
                RunStatus.RUNNING,
                "/api/v1/runs/" + run.id() + "/events",
                new Limits(PUBLISHED.deadline().toSeconds(), PUBLISHED.perIpPerHour(), PUBLISHED.maxActive()));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/runs/" + run.id()))
                .body(body);
    }

    /** {runId, status} while running; the same body as the report event once done. */
    @GetMapping("/{id}")
    ResponseEntity<Object> status(@PathVariable String id) {
        Run run = service.find(id).orElseThrow(RunNotFoundException::new);
        Object body = run.report().<Object>map(report -> report).orElseGet(() -> new Status(run.id(), run.status()));
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id) {
        Run run = service.find(id).orElseThrow(RunNotFoundException::new);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        try {
            // SSE headers go out with the first bytes; this comment tells the client at once that the stream
            // is open (an event with no data is ignored by EventSource).
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        Runnable cancel = run.subscribe(new RunSink() {
            @Override
            public void send(RunEvent event) throws Exception {
                // No "id:" field (no replay). The data is one compact JSON line.
                emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
            }

            @Override
            public void close() {
                emitter.complete();
            }
        });
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        return emitter;
    }
}
