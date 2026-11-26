package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/**
 * N-3a: the connector's own timeouts, read from the running Tomcat (not from a property file): a client that
 * stalls mid-request is dropped after 5 s, while an idle keep-alive connection may wait 60 s for its next
 * request (a browser's event stream and a visitor's next click).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TomcatTimeoutsTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("REQ-API-009 the connector gives a stalled request 5 s and an idle keep-alive connection 60 s")
    void connectorTimeouts() {
        TomcatWebServer server = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        AbstractHttp11Protocol<?> protocol =
                (AbstractHttp11Protocol<?>) server.getTomcat().getConnector().getProtocolHandler();

        assertThat(protocol.getConnectionTimeout())
                .as("connection timeout (ms)")
                .isEqualTo(5_000);
        assertThat(protocol.getKeepAliveTimeout()).as("keep-alive timeout (ms)").isEqualTo(60_000);
    }
}
