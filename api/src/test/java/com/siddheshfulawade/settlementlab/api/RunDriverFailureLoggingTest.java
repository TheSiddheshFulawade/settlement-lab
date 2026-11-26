package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * N-2: ending a failed run must not depend on logging working. If the log call throws (a broken appender,
 * even an Error), the run is still ended: otherwise it sits RUNNING for 60 s and holds its slot.
 */
class RunDriverFailureLoggingTest {

    private static final RunRequest QUICK =
            new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 20, 0);

    /** Throws an Error from the first append it sees, then behaves. */
    private static final class ExplodingOnceAppender extends AppenderBase<ILoggingEvent> {
        final AtomicBoolean exploded = new AtomicBoolean();

        @Override
        protected void append(ILoggingEvent event) {
            if (exploded.compareAndSet(false, true)) {
                throw new AssertionError("injected: the appender failed");
            }
        }
    }

    private final Logger driverLogger = (Logger) LoggerFactory.getLogger(RunDriver.class);
    private final ExplodingOnceAppender appender = new ExplodingOnceAppender();
    private Level before;
    private RunService service;

    @BeforeEach
    void attachAppender() {
        before = driverLogger.getLevel();
        driverLogger.setLevel(Level.WARN);
        appender.setContext(driverLogger.getLoggerContext());
        appender.start();
        driverLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        driverLogger.detachAppender(appender);
        appender.stop();
        driverLogger.setLevel(before);
        if (service != null) {
            service.close();
        }
    }

    @ParameterizedTest(name = "driver failure is an Error: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("REQ-RUN-005 a failure in the driver ends the run even if the log call throws")
    void runIsEndedEvenIfLoggingThrows(boolean failureIsAnError) {
        SettlementRuntime broken = mock(SettlementRuntime.class);
        when(broken.engine())
                .thenThrow(failureIsAnError ? new AssertionError("injected Error") : new IllegalStateException("x"));
        service = new RunService(
                RunsProperties.defaults().withMaxActive(1).withTransfersPerSecond(1_000_000),
                Clock.systemUTC(),
                (settings, listener) -> broken);

        Run run = service.start(QUICK, "198.51.100.111");
        run.awaitDriver(Duration.ofSeconds(10).toNanos());

        assertThat(appender.exploded).as("the log call really threw").isTrue();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(run.isEnded())
                        .as("ended promptly, not left RUNNING until the 60 s expiry")
                        .isTrue());
    }
}
