package forge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engine 5: event loops. Each loop is ONE thread watching MANY connections through a Selector.
 *
 *  - nio1: a single loop (the Node.js model). One thread for everything.
 *  - nio : one loop per CPU core (the Netty model). Loop 0 also accepts new connections
 *          and hands each one to a loop in round-robin order.
 *
 * Rules that keep it correct:
 *  1. A connection belongs to exactly one loop, and only that loop's thread touches it.
 *  2. Other threads never touch a connection directly. They put a task on the loop's
 *     thread-safe queue and call selector.wakeup() so the loop runs it promptly.
 *  3. Never block a loop thread. The simulated `waitMs` uses a timer instead of sleep.
 *     (CPU-heavy work DOES block the loop. The CPU benchmark shows what that costs.)
 */
final class NioEngine implements Engine {

    private static final class Conn {
        final SocketChannel channel;
        final Loop loop;
        SelectionKey key;
        final ByteBuffer in = ByteBuffer.allocate(Http.MAX_HEAD_BYTES);   // received, not yet parsed
        ByteBuffer out;                 // bytes still to send (null if nothing pending)
        boolean closeAfterWrite;
        boolean waiting;                // a delayed response is scheduled
        boolean closed;
        Conn(SocketChannel channel, Loop loop) { this.channel = channel; this.loop = loop; }
    }

    private final int port;
    private final int loopCount;
    private final Router router;
    private final Stats stats;
    private Loop[] loops;
    private ServerSocketChannel server;
    private volatile boolean running;
    private final AtomicInteger nextLoop = new AtomicInteger();
    private final ScheduledExecutorService timer;

    NioEngine(int port, Router router, Stats stats, int loops) {
        this.port = port;
        this.router = router;
        this.stats = stats;
        this.loopCount = Math.max(1, loops);
        this.timer = Executors.newScheduledThreadPool(Math.min(4, loopCount), r -> {
            Thread t = new Thread(r, "forge-nio-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() throws IOException {
        stats.engine = name();
        loops = new Loop[loopCount];
        for (int i = 0; i < loopCount; i++) loops[i] = new Loop(i);

        server = ServerSocketChannel.open();
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        server.bind(new InetSocketAddress(port), 1024);
        server.configureBlocking(false);
        server.register(loops[0].selector, SelectionKey.OP_ACCEPT);   // loop 0 accepts

        running = true;
        for (Loop l : loops) l.thread.start();
    }

    @Override
    public void stop() {
        running = false;
        for (Loop l : loops) l.selector.wakeup();
        for (Loop l : loops) {
            try { l.thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (SelectionKey key : l.selector.keys()) {
                try { key.channel().close(); } catch (IOException ignored) {}
            }
            try { l.selector.close(); } catch (IOException ignored) {}
        }
        timer.shutdownNow();
    }

    @Override public String name() { return loopCount == 1 ? "nio1" : "nio"; }

    /** One event-loop thread and the connections it owns. */
    private final class Loop {
        final Selector selector;
        final Thread thread;
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        Loop(int id) throws IOException {
            selector = Selector.open();
            thread = new Thread(this::run, "forge-nio-loop-" + id);
        }

        /** Thread-safe: run this on the loop thread as soon as possible. */
        void execute(Runnable task) {
            tasks.add(task);
            selector.wakeup();
        }

        void run() {
            while (running) {
                try {
                    selector.select();                            // sleeps until something is ready
                    Runnable task;
                    while ((task = tasks.poll()) != null) task.run();

                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        Conn conn = (Conn) key.attachment();
                        try {
                            if (!key.isValid()) continue;
                            if (key.isAcceptable()) accept();
                            else if (key.isReadable()) onReadable(conn);
                            else if (key.isWritable()) flush(conn);
                        } catch (IOException | CancelledKeyException e) {
                            if (conn != null) close(conn);
                        }
                    }
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }

        /** Registers a new connection with THIS loop. Must run on this loop's thread. */
        void adopt(SocketChannel ch) {
            try {
                Conn conn = new Conn(ch, this);
                conn.key = ch.register(selector, SelectionKey.OP_READ, conn);
            } catch (IOException e) {
                stats.active.decrementAndGet();
                try { ch.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------ per-connection logic

    private void accept() throws IOException {
        SocketChannel ch;
        while ((ch = server.accept()) != null) {                  // drain every pending connection
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            stats.active.incrementAndGet();
            Loop target = loops[Math.floorMod(nextLoop.getAndIncrement(), loops.length)];
            SocketChannel accepted = ch;
            if (target == loops[0]) target.adopt(accepted);         // already on loop 0's thread
            else target.execute(() -> target.adopt(accepted));     // rule 2: hand it over, don't touch
        }
    }

    private void onReadable(Conn c) throws IOException {
        int n = c.channel.read(c.in);
        if (n < 0) { close(c); return; }                            // client closed
        process(c);
    }

    /** Looks for a complete request head in the buffer and answers it. */
    private void process(Conn c) throws IOException {
        if (c.waiting || c.out != null || c.closed) return;         // one request at a time per connection

        byte[] data = c.in.array();
        int len = c.in.position();
        int end = indexOfHeadEnd(data, len);
        if (end < 0) {
            if (len == c.in.capacity()) respondError(c, 431, "request head too large");
            return;                                                 // wait for more bytes
        }

        String head = new String(data, 0, end + 4, StandardCharsets.ISO_8859_1);
        // remove the consumed bytes, keep any extra (a following pipelined request)
        System.arraycopy(data, end + 4, data, 0, len - (end + 4));
        c.in.position(len - (end + 4));

        Request req;
        try {
            req = Http.parseHead(head);
        } catch (Http.BadRequest bad) {
            respondError(c, bad.status, bad.getMessage());
            return;
        }

        Response resp = router.route(req);
        if (resp.waitMs() > 0) {
            c.waiting = true;
            c.key.interestOps(0);                                    // ignore this socket until the timer fires
            timer.schedule(() -> c.loop.execute(() -> {
                c.waiting = false;
                try { respond(c, req, resp); } catch (IOException | CancelledKeyException e) { close(c); }
            }), resp.waitMs(), TimeUnit.MILLISECONDS);
        } else {
            respond(c, req, resp);
        }
    }

    private void respond(Conn c, Request req, Response resp) throws IOException {
        if (c.closed) return;
        boolean keepAlive = req.keepAlive() && running;
        c.out = ByteBuffer.wrap(Http.serialize(resp, keepAlive, req.method().equals("HEAD")));
        c.closeAfterWrite = !keepAlive;
        flush(c);
    }

    private void respondError(Conn c, int status, String message) throws IOException {
        c.out = ByteBuffer.wrap(Http.serialize(Response.text(status, message + "\n"), false, false));
        c.closeAfterWrite = true;
        flush(c);
    }

    /** Writes as much as the socket accepts; asks to be told when it can take more. */
    private void flush(Conn c) throws IOException {
        if (c.out == null || c.closed) return;
        c.channel.write(c.out);
        if (c.out.hasRemaining()) {
            c.key.interestOps(SelectionKey.OP_WRITE);                // socket buffer full: come back later
            return;
        }
        c.out = null;
        if (c.closeAfterWrite) {
            close(c);
        } else {
            c.key.interestOps(SelectionKey.OP_READ);
            process(c);                                              // a pipelined request may already be buffered
        }
    }

    private void close(Conn c) {
        if (c.closed) return;
        c.closed = true;
        stats.active.decrementAndGet();
        if (c.key != null) c.key.cancel();
        try { c.channel.close(); } catch (IOException ignored) {}
    }

    /** Index of the first "\r\n\r\n" in data[0..len), or -1. */
    private static int indexOfHeadEnd(byte[] data, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') return i;
        }
        return -1;
    }
}
