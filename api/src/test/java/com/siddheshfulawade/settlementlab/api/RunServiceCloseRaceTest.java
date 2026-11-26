package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L-11: a start that loses the race with close() is a refusal (503 busy), never an unhandled 500. */
class RunServiceCloseRaceTest {

    private static final RunRequest QUICK = new RunRequest(
            LedgerChoice.CAS, com.siddheshfulawade.settlementlab.pipeline.ThreadMode.VIRTUAL, FaultChoice.NONE, 20, 0);

    private final RunsProperties properties = RunsProperties.defaults()
            .withMaxActive(1)
            .withPerIpPerHour(1_000)
            .withGlobalPerHour(1_000)
            .withTransfersPerSecond(1_000_000);

    private final AtomicReference<RunService> serviceRef = new AtomicReference<>();

    @AfterEach
    void closeService() {
        RunService service = serviceRef.get();
        if (service != null) {
            service.close();
        }
    }

    @Test
    @DisplayName(
            "REQ-RUN-005 a start that races close() fails with ServiceClosedException, closes its engine and stores nothing")
    void startRacingCloseIsServiceClosed() {
        SettlementRuntime runtime = mock(SettlementRuntime.class);
        // the engine is built, and only then does close() win the race
        RunService service = new RunService(properties, Clock.systemUTC(), (settings, listener) -> {
            serviceRef.get().close();
            return runtime;
        });
        serviceRef.set(service);

        assertThatThrownBy(() -> service.start(QUICK, "198.51.100.93"))
                .as("the 503 type, not RejectedExecutionException (a 500)")
                .isInstanceOf(ServiceClosedException.class);

        verify(runtime, times(1)).close();
        assertThat(service.storedRuns()).as("nothing stays stored").isZero();
        assertThatThrownBy(() -> service.start(QUICK, "198.51.100.94"))
                .as("and the service stays closed")
                .isInstanceOf(ServiceClosedException.class);
    }
}
