package forge;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A response the router wants sent.
 *
 * waitMs is the key design trick: the router never sleeps itself. It just says
 * "pretend this request waits on a database for N ms". Each engine then decides
 * HOW to wait: blocking engines sleep their thread, the NIO engine sets a timer
 * and frees its single thread. That difference is what the benchmark measures.
 */
public record Response(int status, String contentType, byte[] body, long waitMs,
                       Map<String, String> extraHeaders) {

    public static Response text(int status, String text) {
        return new Response(status, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8), 0, Map.of());
    }

    public static Response json(String json) {
        return new Response(200, "application/json",
                json.getBytes(StandardCharsets.UTF_8), 0, Map.of());
    }

    public static Response bytes(String contentType, byte[] body) {
        return new Response(200, contentType, body, 0, Map.of());
    }

    public Response withWait(long ms) {
        return new Response(status, contentType, body, ms, extraHeaders);
    }

    public Response withHeader(String name, String value) {
        return new Response(status, contentType, body, waitMs, Map.of(name, value));
    }
}
