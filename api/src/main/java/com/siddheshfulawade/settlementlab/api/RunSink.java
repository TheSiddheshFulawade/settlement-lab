package com.siddheshfulawade.settlementlab.api;

/** Where one subscriber's run events go: in the app, an SSE connection. */
interface RunSink {

    void send(RunEvent event) throws Exception;

    void close();
}
