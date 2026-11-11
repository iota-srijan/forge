package forge;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge's load tester.
 *
 *   java -cp out forge.Bench [--scenario=both|io|cpu] [--seconds=3] [--warmup=2] [--repeats=3]
 *                            [--levels=10,100,500,2000] [--cpuLevels=16,64,256]
 *                            [--work=50] [--cpu=5] [--pool=50] [--queue=100] [--engines=single,nio]
 *
 * Methodology (each choice removes a source of misleading numbers):
 *  - The SERVER runs in its own JVM process, so client and server don't share threads,
 *    heap or JIT, and the thread/heap/CPU figures describe the server alone.
 *  - Users ramp up gradually during a WARMUP period that is not measured, so the JIT is
 *    warm and we measure steady state rather than a thundering-herd connect storm.
 *  - Every level is REPEATED and the median run is reported, with min..max to show spread.
 *  - Failures are split by TYPE (503 / connect / timeout / reset / other), so "errors"
 *    are explained rather than just counted.
 *  - Each simulated user is a virtual thread (cheap), so the client is not the bottleneck.
 */
public class Bench {

    /** What one simulated user saw. */
    static final class UserResult {
        final List<Long> latencies = new ArrayList<>();
        int ok, rejected, connectErr, timeoutErr, resetErr, otherErr;
    }

    /** One measured run of one engine at one load level. */
    record Run(double okPerSec, double p50, double p99, double p999,
               double rejPct, double connPct, double timeoutPct, double resetPct, double otherPct,
               int peakThreads, int peakHeapMb, int peakCommittedMb, double avgCpuPct) {
        double errPct() { return connPct + timeoutPct + resetPct + otherPct; }
    }

    /** The reported row: the median run plus the throughput spread over all repeats. */
    record Row(String scenario, String engine, int users, Run median, double minOk, double maxOk) {}

    /** Real HTTP clients back off when told "503, Retry-After: N". Turn off with --retryAfter=ignore. */
    static volatile boolean honorRetryAfter = true;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Map<String, String> opt = new HashMap<>();
        for (String a : args) if (a.startsWith("--") && a.contains("="))
            opt.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));

        int seconds = Integer.parseInt(opt.getOrDefault("seconds", "3"));
        int warmup = Integer.parseInt(opt.getOrDefault("warmup", "2"));
        int repeats = Integer.parseInt(opt.getOrDefault("repeats", "3"));
        int workMs = Integer.parseInt(opt.getOrDefault("work", "50"));
        int cpuMs = Integer.parseInt(opt.getOrDefault("cpu", "5"));
        int pool = Integer.parseInt(opt.getOrDefault("pool", "50"));
        int queue = Integer.parseInt(opt.getOrDefault("queue", "100"));
        String scenario = opt.getOrDefault("scenario", "both");
        honorRetryAfter = !opt.getOrDefault("retryAfter", "honor").equals("ignore");
        int[] ioLevels = ints(opt.getOrDefault("levels", "10,100,1000,5000"));
        int[] cpuLevels = ints(opt.getOrDefault("cpuLevels", "16,64,256"));
        String[] engines = opt.containsKey("engines") ? opt.get("engines").split(",") : Engines.ALL;
        Path outDir = Path.of(opt.getOrDefault("outDir", "."));

        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("Forge benchmark | %d logical cores | %ds measured + %ds warmup | %d repeats (median)%n%n",
                cores, seconds, warmup, repeats);

        List<Row> rows = new ArrayList<>();
        int port = 9100;
        if (!scenario.equals("cpu")) {
            System.out.printf("--- IO scenario: each request WAITS %d ms (like a database call) ---%n", workMs);
            for (String e : engines) rows.addAll(runEngine("io", e, port++, "/work?ms=" + workMs,
                    ioLevels, seconds, warmup, repeats, pool, queue));
        }
        if (!scenario.equals("io")) {
            System.out.printf("%n--- CPU scenario: each request COMPUTES for %d ms ---%n", cpuMs);
            for (String e : engines) rows.addAll(runEngine("cpu", e, port++, "/cpu?ms=" + cpuMs,
                    cpuLevels, seconds, warmup, repeats, pool, queue));
        }

        Files.createDirectories(outDir.resolve("charts"));
        Files.writeString(outDir.resolve("results.md"),
                markdown(rows, workMs, cpuMs, seconds, warmup, repeats, pool, queue, cores));
        Charts.writeAll(rows, outDir.resolve("charts"), workMs, cpuMs, cores);
        System.out.println("\nWrote " + outDir.resolve("results.md").toAbsolutePath()
                + " and charts in " + outDir.resolve("charts").toAbsolutePath());
        System.exit(0);
    }

    // ------------------------------------------------------------------ server process

    static List<Row> runEngine(String scenario, String engine, int port, String path, int[] levels,
                               int seconds, int warmup, int repeats, int pool, int queue) throws Exception {
        int adminPort = port + 500;
        Process server = startServer(engine, port, adminPort, pool, queue);
        List<Row> rows = new ArrayList<>();
        try {
            for (int users : levels) {
                List<Run> runs = new ArrayList<>();
                for (int r = 0; r < repeats; r++) {
                    runs.add(runLevel(port, adminPort, path, users, seconds, warmup));
                    Thread.sleep(300);                                // let connections drain between runs
                }
                runs.sort(Comparator.comparingDouble(Run::okPerSec));
                Run med = runs.get(runs.size() / 2);
                Row row = new Row(scenario, engine, users, med,
                        runs.get(0).okPerSec(), runs.get(runs.size() - 1).okPerSec());
                rows.add(row);
                System.out.printf("%-3s %-7s users=%-5d ok/s=%7.0f [%6.0f..%-6.0f] p50=%7.1f p99=%7.1f p99.9=%7.1f ms"
                                + " | 503=%4.1f%% conn=%4.1f%% timeout=%4.1f%% reset=%4.1f%% other=%4.1f%%"
                                + " | thr=%-5d heap=%-4dMB committed=%-5dMB cpu=%3.0f%%%n",
                        scenario, engine, users, med.okPerSec(), row.minOk(), row.maxOk(),
                        med.p50(), med.p99(), med.p999(), med.rejPct(), med.connPct(), med.timeoutPct(),
                        med.resetPct(), med.otherPct(), med.peakThreads(), med.peakHeapMb(),
                        med.peakCommittedMb(), med.avgCpuPct());
            }
        } finally {
            server.destroy();
            server.waitFor();
            Thread.sleep(500);
        }
        return rows;
    }

    /** Launches `java forge.Main` as a child process and waits until it answers. */
    static Process startServer(String engine, int port, int adminPort, int pool, int queue) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx1g", "-cp", System.getProperty("java.class.path"),
                "forge.Main", "--engine=" + engine, "--port=" + port, "--admin=" + adminPort,
                "--pool=" + pool, "--queue=" + queue);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(System.getProperty("java.io.tmpdir"), "forge-" + engine + "-" + port + ".log"));
        Process p = pb.start();
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (get(port, "/hello", 500) != null) return p;
            Thread.sleep(100);
        }
        p.destroyForcibly();
        throw new IllegalStateException(engine + " server did not start on port " + port);
    }

    // ------------------------------------------------------------------ one measured run

    static Run runLevel(int port, int adminPort, String path, int users, int seconds, int warmupSec) throws Exception {
        long start = System.nanoTime();
        long measureFrom = start + warmupSec * 1_000_000_000L;
        long deadline = measureFrom + seconds * 1_000_000_000L;
        long rampNanos = warmupSec * 1_000_000_000L / 2;              // all users online halfway through warmup

        // Sample the SERVER's own /stats (on its separate admin port) while the measured window runs.
        int[] peakThreads = {0}, peakHeap = {0}, peakCommitted = {0};
        List<Double> cpuSamples = new ArrayList<>();
        Thread sampler = Thread.ofVirtual().start(() -> {
            try {
                while (System.nanoTime() < measureFrom - 300_000_000L) Thread.sleep(20);
                // CPU load is "since the previous call", so the first reading would include idle
                // time from before this run. Take one throwaway reading just before the window opens.
                get(adminPort, "/stats", 2500);
                while (System.nanoTime() < measureFrom) Thread.sleep(20);
                while (System.nanoTime() < deadline) {
                    String json = get(adminPort, "/stats", 2500);
                    if (json != null) {
                        peakThreads[0] = Math.max(peakThreads[0], (int) num(json, "threads"));
                        peakHeap[0] = Math.max(peakHeap[0], (int) num(json, "heapMb"));
                        peakCommitted[0] = Math.max(peakCommitted[0], (int) num(json, "committedMb"));
                        double cpu = num(json, "cpuPct");
                        if (cpu >= 0) cpuSamples.add(cpu);
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException ignored) {}
        });

        List<Future<UserResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < users; i++) {
                long startAt = start + rampNanos * i / Math.max(1, users);
                futures.add(pool.submit(() -> simulateUser(port, path, startAt, measureFrom, deadline)));
            }
        }
        sampler.join();

        List<Long> lat = new ArrayList<>();
        int ok = 0, rej = 0, conn = 0, to = 0, reset = 0, other = 0;
        for (Future<UserResult> f : futures) {
            UserResult r = f.get();
            lat.addAll(r.latencies);
            ok += r.ok; rej += r.rejected; conn += r.connectErr; to += r.timeoutErr;
            reset += r.resetErr; other += r.otherErr;
        }
        long[] s = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        double attempts = Math.max(1, ok + rej + conn + to + reset + other);
        double avgCpu = cpuSamples.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
        return new Run(ok / (double) seconds, pct(s, 0.50), pct(s, 0.99), pct(s, 0.999),
                100 * rej / attempts, 100 * conn / attempts, 100 * to / attempts,
                100 * reset / attempts, 100 * other / attempts,
                peakThreads[0], peakHeap[0], peakCommitted[0], avgCpu);
    }

    /**
     * One user: wait for its ramp slot, then send requests back to back on a kept-alive
     * connection, reconnecting whenever the server answers "Connection: close".
     * Only requests that START inside the measured window are counted.
     */
    static UserResult simulateUser(int port, String path, long startAt, long measureFrom, long deadline) {
        UserResult res = new UserResult();
        byte[] request = ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        Socket socket = null;
        InputStream in = null;

        long wait = startAt - System.nanoTime();
        if (wait > 0) {
            try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException e) { return res; }
        }

        while (System.nanoTime() < deadline) {
            long t0 = System.nanoTime();
            boolean counted = t0 >= measureFrom;
            boolean connecting = false;
            try {
                if (socket == null) {
                    connecting = true;
                    socket = new Socket();
                    releasePortOnClose(socket);
                    socket.connect(new InetSocketAddress("localhost", port), 3000);
                    socket.setSoTimeout(5000);
                    socket.setTcpNoDelay(true);
                    in = new BufferedInputStream(socket.getInputStream());
                    connecting = false;
                }
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();

                String head = Http.readHead(in);
                if (head == null) throw new EOFException("server closed connection");
                String lower = head.toLowerCase();
                int status = Integer.parseInt(head.substring(9, 12));
                in.readNBytes(contentLength(lower));                     // consume body so the next reply lines up

                if (counted) {
                    if (status == 200) { res.ok++; res.latencies.add(System.nanoTime() - t0); }
                    else if (status == 503) res.rejected++;
                    else res.otherErr++;
                }
                if (lower.contains("connection: close")) { closeQuietly(socket); socket = null; }
                if (status == 503 && honorRetryAfter) backOff(lower, deadline);
            } catch (IOException | RuntimeException e) {
                if (counted) {
                    if (connecting || e instanceof ConnectException) res.connectErr++;
                    else if (e instanceof SocketTimeoutException) res.timeoutErr++;
                    else if (e instanceof SocketException || e instanceof EOFException) res.resetErr++;
                    else res.otherErr++;
                }
                closeQuietly(socket);
                socket = null;
                // A real client backs off after a failure instead of reconnecting in a tight loop.
                // Without this, thousands of users hammering a refusing server use up the CLIENT's
                // own ports, and then even the measuring probe cannot connect (showed up as
                // "0 threads, 0 MB" for overloaded engines).
                long left = (deadline - System.nanoTime()) / 1_000_000;
                try { Thread.sleep(Math.max(0, Math.min(100, left))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        closeQuietly(socket);
        return res;
    }

    // ------------------------------------------------------------------ helpers

    /** Sleeps for the server's Retry-After (capped at 1 s, never past the deadline), like a polite client. */
    static void backOff(String lowerHead, long deadline) {
        long ms = 1000;
        int i = lowerHead.indexOf("retry-after:");
        if (i >= 0) {
            try { ms = Long.parseLong(lowerHead.substring(i + 12, lowerHead.indexOf("\r\n", i)).trim()) * 1000; }
            catch (RuntimeException ignored) {}
        }
        ms = Math.min(Math.min(ms, 1000), Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** A one-shot GET on its own connection; returns the body or null on any failure. */
    static String get(int port, String path, int timeoutMs) {
        try (Socket s = new Socket()) {
            releasePortOnClose(s);
            s.connect(new InetSocketAddress("localhost", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = Http.readHead(in);
            if (head == null || !head.startsWith("HTTP/1.1 200")) return null;
            return new String(in.readNBytes(contentLength(head.toLowerCase())), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A normally closed TCP connection keeps its local port reserved for a while (TIME_WAIT).
     * The OS only has about 16,000 such ports, and this client opens far more connections than
     * that in a run, so without this the CLIENT runs out of ports and every later engine shows
     * fake "could not connect" errors. Linger 0 makes close() drop the connection at once.
     * A load-test client can do this safely because it only closes after reading the full reply.
     */
    static void releasePortOnClose(Socket s) {
        try { s.setSoLinger(true, 0); } catch (IOException ignored) {}
    }

    static int contentLength(String lowerHead) {
        int i = lowerHead.indexOf("content-length:");
        if (i < 0) return 0;
        return Integer.parseInt(lowerHead.substring(i + 15, lowerHead.indexOf("\r\n", i)).trim());
    }

    private static final Pattern NUM = Pattern.compile("\"(\\w+)\":(-?[0-9.]+)");

    static double num(String json, String key) {
        Matcher m = NUM.matcher(json);
        while (m.find()) if (m.group(1).equals(key)) return Double.parseDouble(m.group(2));
        return -1;
    }

    static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p))] / 1e6;
    }

    /** 0 means the measuring probe could not reach the server during that run. */
    static String na(int v) { return v <= 0 ? "n/a" : Integer.toString(v); }

    static int[] ints(String csv) { return Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray(); }

    static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ report

    static String markdown(List<Row> rows, int workMs, int cpuMs, int seconds, int warmup, int repeats,
                           int pool, int queue, int cores) {
        StringBuilder sb = new StringBuilder("# Forge benchmark results\n\n");
        sb.append(String.format("Machine: %d logical cores. Server in its own JVM (-Xmx1g). %ds measured after %ds warmup, "
                + "median of %d repeats (min..max throughput shown). Pool engine: %d workers, queue %d.%n%n",
                cores, seconds, warmup, repeats, pool, queue));
        sb.append("Failure columns are % of all attempts: **503** = turned away on purpose, **conn** = could not connect, "
                + "**timeout** = no reply within 5 s, **reset** = connection dropped.\n");
        sb.append("Threads = peak platform (OS) threads in the server process; virtual threads are not OS threads. "
                + "Committed = memory the OS committed to the server process (heap + thread stacks + JVM overhead). "
                + "Clients honor `Retry-After` on a 503: " + honorRetryAfter + ".\n\n");

        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("io", "IO-bound: each request waits " + workMs + " ms");
        titles.put("cpu", "CPU-bound: each request computes for " + cpuMs + " ms");
        for (var t : titles.entrySet()) {
            List<Row> part = rows.stream().filter(r -> r.scenario().equals(t.getKey())).toList();
            if (part.isEmpty()) continue;
            sb.append("## ").append(t.getValue()).append("\n\n");
            sb.append("| Engine | Users | OK req/s | spread | p50 ms | p99 ms | p99.9 ms | 503 % | conn % | timeout % | reset % | Threads | Heap MB | Committed MB | CPU % |\n");
            sb.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (Row r : part) {
                Run m = r.median();
                sb.append(String.format("| %s | %d | %.0f | %.0f..%.0f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %s | %s | %s | %s |%n",
                        r.engine(), r.users(), m.okPerSec(), r.minOk(), r.maxOk(), m.p50(), m.p99(), m.p999(),
                        m.rejPct(), m.connPct(), m.timeoutPct(), m.resetPct(),
                        na(m.peakThreads()), na(m.peakHeapMb()), na(m.peakCommittedMb()), m.avgCpuPct() < 0 ? "n/a" : String.format("%.0f", m.avgCpuPct())));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
