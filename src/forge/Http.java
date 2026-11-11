package forge;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Hand-written HTTP/1.1 parsing and writing. No frameworks.
 *
 * Wire format of a request (what a browser actually sends):
 *   GET /work?ms=50 HTTP/1.1\r\n
 *   Host: localhost\r\n
 *   Connection: keep-alive\r\n
 *   \r\n
 * The blank line ("\r\n\r\n") marks the end of the head. We only support
 * requests without a body (GET/HEAD), which is all a benchmark target needs.
 */
public final class Http {

    public static final int MAX_HEAD_BYTES = 16 * 1024;

    private Http() {}

    /** Thrown for a malformed or oversized request; carries the status to answer with. */
    public static class BadRequest extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Reads bytes until the blank line that ends the head.
     * Returns null if the peer closed the connection cleanly before sending anything
     * (normal at the end of a keep-alive connection).
     */
    public static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int matched = 0;                       // how many chars of "\r\n\r\n" we have seen in a row
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > MAX_HEAD_BYTES) {
                throw new BadRequest(431, "request head too large");
            }
            // track the pattern \r \n \r \n
            if ((matched == 0 || matched == 2) && b == '\r') matched++;
            else if ((matched == 1 || matched == 3) && b == '\n') matched++;
            else matched = (b == '\r') ? 1 : 0;

            if (matched == 4) {
                return buf.toString(StandardCharsets.ISO_8859_1);
            }
        }
        if (buf.size() == 0) return null;      // clean close
        throw new EOFException("connection closed mid-request");
    }

    /** Turns the raw head text into a Request. */
    public static Request parseHead(String head) throws BadRequest {
        String[] lines = head.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3) throw new BadRequest(400, "bad request line");

        String method = first[0];
        String target = first[1];
        String version = first[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new BadRequest(505, "unsupported version");
        }

        String path = target;
        Map<String, String> query = new HashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    query.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                              URLDecoder.decode(v, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new BadRequest(400, "bad query encoding");
                }
            }
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequest(400, "bad header");
            headers.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
        }

        // HTTP/1.1 keeps the connection open by default; 1.0 closes unless asked.
        String conn = headers.getOrDefault("connection", "").toLowerCase();
        boolean keepAlive = version.equals("HTTP/1.1") ? !conn.contains("close")
                                                       : conn.contains("keep-alive");
        return new Request(method, path, query, headers, keepAlive);
    }

    /** Builds the exact bytes to put on the wire. */
    public static byte[] serialize(Response r, boolean keepAlive, boolean headOnly) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(r.status()).append(' ').append(reason(r.status())).append("\r\n");
        sb.append("Content-Type: ").append(r.contentType()).append("\r\n");
        sb.append("Content-Length: ").append(r.body().length).append("\r\n");
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        sb.append("Server: Forge\r\n");
        r.extraHeaders().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (headOnly) return head;
        byte[] all = new byte[head.length + r.body().length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(r.body(), 0, all, head.length, r.body().length);
        return all;
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 431 -> "Request Header Fields Too Large";
            case 503 -> "Service Unavailable";
            case 505 -> "HTTP Version Not Supported";
            default -> "Status";
        };
    }
}
