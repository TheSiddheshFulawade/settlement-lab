package com.siddheshfulawade.settlementlab.api;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Ends every event stream as soon as shutdown starts (REQ-API-006).
 *
 * <p>Invariant: streams end before the web server's graceful phase waits. An SSE request never
 * finishes by itself, so an open stream would hold that wait for the whole phase timeout. Spring
 * publishes {@link ContextClosedEvent} first in {@code close()}, before any lifecycle bean is
 * stopped (the graceful phase included), so no phase number has to be chosen or kept in step with
 * Spring Boot. Closing is idempotent, so the hub's own destroy method may close it again later.
 */
class EventStreamShutdown implements ApplicationListener<ContextClosedEvent> {

    private final EventHub hub;

    EventStreamShutdown(EventHub hub) {
        this.hub = hub;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        hub.close();
    }
}
