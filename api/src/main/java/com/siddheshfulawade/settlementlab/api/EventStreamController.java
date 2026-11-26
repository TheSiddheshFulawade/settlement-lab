package com.siddheshfulawade.settlementlab.api;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code GET /api/v1/events}: a server-sent event per processed transfer (REQ-API-005). */
@Profile("!prod") // REQ-API-010: the prod profile serves only the runs API and health
@RestController
@RequestMapping("/api/v1/events")
class EventStreamController {

    /** A client reconnects after this; keeps abandoned connections from living forever. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final EventHub hub;

    EventStreamController(EventHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        try {
            // Servers send SSE headers only with the first bytes; this comment tells the client at
            // once that the stream is open (an "event" with no data is ignored by EventSource).
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        Runnable cancel = hub.subscribe(new EventHub.Sink() {
            @Override
            public void send(OutcomeEvent event) throws Exception {
                // No SSE "id:" field: the transfer id is client input and may hold a line separator,
                // which makes the emitter throw and would drop every subscriber. The id is in the JSON
                // data (escaped there); there is no resume support, so the field has no use.
                emitter.send(SseEmitter.event().name("outcome").data(event, MediaType.APPLICATION_JSON));
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
