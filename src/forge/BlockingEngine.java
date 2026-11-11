package forge;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Shared skeleton for the four engines that use classic blocking sockets.
 *
 * The ONLY thing that differs between them is `dispatch()`: who runs
 * serveConnection() for a freshly accepted socket?
 *   - single-threaded: the accept thread itself (everyone else waits)
 *   - thread-per-connection: a brand new platform thread
 *   - pool: a worker from a fixed pool (or a 503 if the pool is full)
 *   - virtual: a brand new virtual thread
 */
abstract class BlockingEngine implements Engine {

    protected final int port;
    protected final Router router;
    protected final Stats stats;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    protected volatile boolean running;

    BlockingEngine(int port, Router router, Stats stats) {
        this.port = port;
        this.router = router;
        this.stats = stats;
    }

    /** Hand a freshly accepted connection to whichever thread should serve it. */
    protected abstract void dispatch(Socket socket);

    /** Whether to keep a connection open for more requests after answering one. */
    protected boolean allowKeepAlive() { return true; }

    /** Called from stop() so subclasses can shut their executors down. */
    protected void onStop() {}

    @Override
    public void start() throws IOException {
        stats.engine = name();
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        // backlog 1024: how many not-yet-accepted connections the OS may queue for us
        serverSocket.bind(new InetSocketAddress(port), 1024);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "forge-accept-" + name());
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();     // blocks until a client connects
                stats.active.incrementAndGet();
                dispatch(s);
            } catch (IOException e) {
                if (running) e.printStackTrace();     // closed on purpose during stop(): stay quiet
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) {}   // wakes accept() with an exception
        try { acceptThread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        onStop();
    }

    /**
     * Talks to one client until it is done. Runs on whatever thread dispatch() chose.
     * A try/finally guarantees the socket is closed and the counter is fixed,
     * even if something throws (the original tutorial code leaked sockets).
     */
    protected void serveConnection(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);                 // an idle or silent client can't hold us forever
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            while (true) {
                String head;
                try {
                    head = Http.readHead(in);
                } catch (SocketTimeoutException idle) {
                    break;                             // client went quiet: drop the connection
                }
                if (head == null) break;               // client closed the connection

                Request req;
                try {
                    req = Http.parseHead(head);
                } catch (Http.BadRequest bad) {
                    out.write(Http.serialize(Response.text(bad.status, bad.getMessage() + "\n"), false, false));
                    out.flush();
                    break;
                }

                Response resp = router.route(req);
                if (resp.waitMs() > 0) {
                    Thread.sleep(resp.waitMs());       // BLOCKING wait: this thread can do nothing else
                }

                boolean keepAlive = allowKeepAlive() && req.keepAlive() && running;
                out.write(Http.serialize(resp, keepAlive, req.method().equals("HEAD")));
                out.flush();
                if (!keepAlive) break;
            }
        } catch (IOException ignored) {
            // client vanished mid-conversation: nothing useful to do
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stats.active.decrementAndGet();
        }
    }
}
