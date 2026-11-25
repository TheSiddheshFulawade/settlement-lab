package com.siddheshfulawade.settlementlab.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A raw loopback server that answers badly on purpose: nothing at all, the headers then one byte at a time
 * forever, or the headers then a flood. It is how the load check's own limits are tested.
 */
final class MisbehavingServer implements AutoCloseable {

    enum Mode {
        /** Accepts the request and never answers. */
        SILENT,
        /** Sends the headers, then one byte every 20 ms, forever. */
        DRIP,
        /** Sends the headers, then 8 KB blocks as fast as the client reads, up to 64 MB. */
        FLOOD
    }

    private static final long FLOOD_LIMIT = 64L * 1024 * 1024;

    private final ServerSocket socket;
    private final Mode mode;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger finishedWriters = new AtomicInteger();
    private volatile long bytesWritten;

    MisbehavingServer(Mode mode) throws IOException {
        this.mode = mode;
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    String url() {
        return "http://localhost:" + socket.getLocalPort();
    }

    int connections() {
        return connections.get();
    }

    /** Connections whose answer ended: the client went away, or the server ran out of things to send. */
    int finishedWriters() {
        return finishedWriters.get();
    }

    long bytesWritten() {
        return bytesWritten;
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                connections.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            readRequestHead(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (mode == Mode.SILENT) {
                while (client.getInputStream().read() != -1) {
                    // swallow any request body; wait until the client gives up and closes
                }
                return;
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            writeBody(out);
        } catch (IOException | InterruptedException ignored) {
            // the client closed the connection: that is what the tests want to see
        } finally {
            finishedWriters.incrementAndGet();
        }
    }

    private void writeBody(OutputStream out) throws IOException, InterruptedException {
        if (mode == Mode.DRIP) {
            while (true) {
                out.write('x');
                out.flush();
                bytesWritten++;
                Thread.sleep(20); // the point of this server is to be slow
            }
        }
        byte[] block = new byte[8192];
        Arrays.fill(block, (byte) 'x');
        while (bytesWritten < FLOOD_LIMIT) {
            out.write(block);
            bytesWritten += block.length;
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            matched = (b == '\r' || b == '\n') && (matched % 2 == (b == '\r' ? 0 : 1)) ? matched + 1 : 0;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
