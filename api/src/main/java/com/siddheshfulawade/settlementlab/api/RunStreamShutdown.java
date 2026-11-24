package com.siddheshfulawade.settlementlab.api;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Ends every run, and so every run event stream, as soon as shutdown starts (REQ-API-006), for the same
 * reason as {@link EventStreamShutdown}: a stream never ends by itself, so an open one would hold the web
 * server's graceful phase for its whole timeout. Closing is idempotent, so the service's own destroy
 * method may close it again later.
 */
class RunStreamShutdown implements ApplicationListener<ContextClosedEvent> {

    private final RunService service;

    RunStreamShutdown(RunService service) {
        this.service = service;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        service.close();
    }
}
