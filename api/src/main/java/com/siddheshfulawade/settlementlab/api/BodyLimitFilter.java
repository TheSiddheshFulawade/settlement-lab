package com.siddheshfulawade.settlementlab.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps a request body (any method except GET, HEAD and OPTIONS) at 16 KB (REQ-API-009): 413 {@code body-too-large}, before anything is parsed or
 * submitted.
 *
 * <p>Invariant: the filter never holds more than {@code MAX_BYTES + 1} bytes of a body, whatever the
 * client sends. A declared {@code Content-Length} over the limit is refused without reading a byte. A
 * chunked body has no declared length, so the filter reads at most one byte past the limit: if that byte
 * exists the body is too large and the rest is never read; otherwise the bytes it read are handed on and
 * the handler sees the body as if it had read the socket itself.
 */
final class BodyLimitFilter extends OncePerRequestFilter implements Ordered {

    static final int MAX_BYTES = 16 * 1024;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20; // after CORS, so the 413 carries the CORS headers
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // By method, not by path: "/runs;x=1" or "/%72uns" reach the same handler as "/runs", so a list of
        // paths could be walked around. Every method that may carry a body is capped (PUT, PATCH, DELETE too);
        // no endpoint here needs a larger one.
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new ReplayedBody(request, body), response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write("{\"type\":\"" + Problems.TYPE_BASE + "body-too-large\","
                        + "\"title\":\"Request body too large\",\"status\":413,"
                        + "\"detail\":\"A request body may be at most 16 KB.\"}");
    }

    /** The request with its already-read body served again from memory. */
    private static final class ReplayedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("the body is already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String name = getCharacterEncoding();
            Charset charset = name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
