package forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides what to answer for each request. Engine-agnostic: every engine calls
 * route() and then handles any `waitMs` in its own way.
 */
public final class Router {

    private final Stats stats;
    private final Path wwwRoot;

    public Router(Stats stats, Path wwwRoot) {
        this.stats = stats;
        this.wwwRoot = wwwRoot.toAbsolutePath().normalize();
    }

    public Response route(Request req) {
        stats.total.increment();

        boolean get = req.method().equals("GET");
        if (!get && !req.method().equals("HEAD")) {
            return Response.text(405, "only GET and HEAD are supported\n").withHeader("Allow", "GET, HEAD");
        }

        switch (req.path()) {
            case "/":
                return Response.text(200, "Forge is running. engine=" + stats.engine + "\n");
            case "/hello":
                return Response.text(200, "Hello from Forge\n");
            case "/work": {
                // Simulates waiting on a database / remote API for ms milliseconds.
                long ms = parseLong(req.query("ms", "50"), 50);
                ms = Math.max(0, Math.min(ms, 2000));          // cap so nobody can park us forever
                return Response.text(200, "done after " + ms + "ms\n").withWait(ms);
            }
            case "/cpu": {
                // The opposite of /work: burns real CPU for ms milliseconds ON THE CALLING THREAD.
                // Waiting can be overlapped; computing can't. This shows the limits of each engine.
                long ms = parseLong(req.query("ms", "5"), 5);
                ms = Math.max(0, Math.min(ms, 200));
                long result = burnCpu(ms);
                return Response.text(200, "computed " + ms + "ms (" + (result & 0xff) + ")\n");
            }
            case "/stats":
                return Response.json(stats.json());
            case "/dashboard":
                return staticFile("dashboard.html");
            default:
                return staticFile(req.path().substring(1));
        }
    }

    /**
     * Serves a file from the www folder.
     * Security: a request for /../../secret.txt must NOT escape the www folder.
     * We resolve the path, normalise away any "..", and require the result to
     * still start with the www root. Anything else is a 403.
     */
    private Response staticFile(String relative) {
        if (relative.isEmpty()) return Response.text(404, "not found\n");
        try {
            Path target = wwwRoot.resolve(relative).normalize();
            if (!target.startsWith(wwwRoot)) return Response.text(403, "forbidden\n");
            if (!Files.isRegularFile(target)) return Response.text(404, "not found\n");
            return Response.bytes(contentType(target.getFileName().toString()), Files.readAllBytes(target));
        } catch (IOException | RuntimeException e) {     // RuntimeException: e.g. invalid path characters
            return Response.text(404, "not found\n");
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /**
     * How many work units take 1 ms of CPU on this machine, measured once at startup.
     *
     * Why not just "spin until 5 ms of wall-clock time pass"? Because with more threads
     * than cores, a thread gets paused by the OS mid-spin, and the paused time would count
     * as "work". Requests would secretly do less computing, and engines that oversubscribe
     * the CPU would look faster than the hardware allows. A FIXED amount of work per
     * request makes every engine do the same job.
     */
    public static final long UNITS_PER_MS = calibrate();

    private static long calibrate() {
        work(200_000);                                        // warm up the JIT first
        long units = 0, t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 200_000_000L) {       // measure for 200 ms
            work(100);
            units += 100;
        }
        return Math.max(1, units / 200);
    }

    /** One unit = 1000 xorshift steps. Returns the value so the JIT can't delete the loop. */
    private static long work(long units) {
        long x = 0x9E3779B97F4A7C15L;
        for (long u = 0; u < units; u++) {
            for (int i = 0; i < 1000; i++) {
                x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            }
        }
        return x;
    }

    private static long burnCpu(long ms) {
        return work(ms * UNITS_PER_MS);
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
