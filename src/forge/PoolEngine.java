package forge;

import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Engine 3: a fixed pool of worker threads plus a BOUNDED waiting queue.
 *
 * The video used Executors.newFixedThreadPool(10), whose queue is unbounded:
 * under overload the queue grows until memory runs out. Here the queue has a
 * hard limit. When pool AND queue are full, the acceptor answers
 * "503 Service Unavailable, Retry-After: 1" itself and closes the connection.
 * Failing fast and politely beats failing slowly and fatally.
 *
 * Keep-alive is OFF: a keep-alive connection would pin one worker for as long
 * as the client stays connected, starving everyone queued behind it.
 */
final class PoolEngine extends BlockingEngine {

    private final ThreadPoolExecutor pool;

    PoolEngine(int port, Router router, Stats stats, int poolSize, int queueSize) {
        super(port, router, stats);
        this.pool = new ThreadPoolExecutor(
                poolSize, poolSize,                       // core = max: a truly fixed number of workers
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),      // the bounded waiting room
                new ThreadPoolExecutor.AbortPolicy());    // when full: throw RejectedExecutionException
        stats.queueDepth = () -> pool.getQueue().size();
    }

    @Override
    protected void dispatch(Socket socket) {
        try {
            pool.execute(() -> serveConnection(socket));
        } catch (RejectedExecutionException full) {
            // Answer the 503 on a throwaway virtual thread so the accept loop never stalls
            // while we politely read and reply to a client we are turning away.
            Thread.ofVirtual().name("forge-reject").start(() -> reject(socket));
        }
    }

    /**
     * Why read the request before replying?
     * If we close a socket while the client's request bytes are still unread in our
     * receive buffer, TCP sends a RESET instead of a normal close. The client then sees
     * "connection reset" and often never reads our 503. Reading the head first, then
     * half-closing (shutdownOutput) lets the 503 arrive cleanly.
     */
    private void reject(Socket socket) {
        stats.rejected.increment();
        try (socket) {
            socket.setSoTimeout(500);
            try { Http.readHead(new java.io.BufferedInputStream(socket.getInputStream())); }
            catch (java.io.IOException ignored) { /* slow or broken client: reply anyway */ }
            Response busy = Response.text(503, "server busy, try again shortly\n").withHeader("Retry-After", "1");
            socket.getOutputStream().write(Http.serialize(busy, false, false));
            socket.getOutputStream().flush();
            socket.shutdownOutput();                    // send FIN: "I'm done talking"
        } catch (java.io.IOException ignored) {
        } finally {
            stats.active.decrementAndGet();
        }
    }

    @Override protected boolean allowKeepAlive() { return false; }

    @Override
    protected void onStop() {
        pool.shutdownNow();                // interrupts workers sleeping in Thread.sleep
    }

    @Override public String name() { return "pool"; }
}
