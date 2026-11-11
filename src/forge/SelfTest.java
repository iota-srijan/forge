package forge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * No-dependency test suite: runs the same HTTP edge cases against every engine.
 *   java -cp out forge.SelfTest
 * Exit code 0 = everything passed.
 */
public class SelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        int port = 9400;
        for (String name : Engines.ALL) {
            Engines.Built b = Engines.create(name, port, Path.of("www"), 4, 4);
            b.engine().start();
            System.out.println("== " + name);
            try {
                runChecks(name, port);
            } finally {
                b.engine().stop();
            }
            port++;
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void runChecks(String engine, int port) throws Exception {
        boolean keepAliveEngine = !engine.equals("single") && !engine.equals("pool");

        String r = raw(port, "GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("GET /hello -> 200 with body", r.startsWith("HTTP/1.1 200") && r.endsWith("Hello from Forge\n"));

        r = raw(port, "HEAD /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("HEAD -> headers only, real Content-Length", r.startsWith("HTTP/1.1 200")
                && r.contains("Content-Length: 17") && r.endsWith("\r\n\r\n"));

        r = raw(port, "POST /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("POST -> 405 with Allow header", r.startsWith("HTTP/1.1 405") && r.contains("Allow: GET, HEAD"));

        r = raw(port, "GET /../src/forge/Main.java HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("path traversal ../ -> 403", r.startsWith("HTTP/1.1 403"));

        r = raw(port, "GET /dashboard HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("static file /dashboard -> 200 html", r.startsWith("HTTP/1.1 200") && r.contains("text/html"));

        r = raw(port, "GET /missing.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("missing file -> 404", r.startsWith("HTTP/1.1 404"));

        r = raw(port, "GARBAGE\r\n\r\n");
        check("malformed request line -> 400", r.startsWith("HTTP/1.1 400"));

        r = raw(port, "GET / HTTP/2.0\r\nHost: x\r\n\r\n");
        check("unsupported version -> 505", r.startsWith("HTTP/1.1 505"));

        r = raw(port, "GET /hello HTTP/1.0\r\n\r\n");
        check("HTTP/1.0 defaults to Connection: close", r.contains("Connection: close"));

        // Two requests in ONE write (pipelining): both must be answered, in order.
        r = raw(port, "GET /hello HTTP/1.1\r\nHost: x\r\n\r\nGET /missing HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        if (keepAliveEngine) {
            check("pipelined requests -> 2 responses in order",
                    count(r, "HTTP/1.1 ") == 2 && r.indexOf("200 OK") < r.indexOf("404 Not Found"));
        } else {
            check("no keep-alive engine answers first, then closes",
                    count(r, "HTTP/1.1 ") == 1 && r.contains("Connection: close"));
        }

        long t0 = System.nanoTime();
        r = raw(port, "GET /work?ms=120 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("/work?ms=120 really waits (took " + ms + " ms)", r.startsWith("HTTP/1.1 200") && ms >= 115);

        r = raw(port, "GET /hello HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(20_000) + "\r\n\r\n");
        check("oversized head -> 431 (or dropped)", r.startsWith("HTTP/1.1 431") || r.isEmpty());
    }

    /** Sends bytes, then reads until the server closes or goes quiet for 1.5 s. */
    static String raw(int port, String request) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 2000);
            s.setSoTimeout(1500);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            } catch (SocketTimeoutException quiet) {
                // keep-alive connection left open: what we have is the answer
            } catch (IOException reset) {
                // server dropped us (e.g. after 431): return what arrived
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    }
}
