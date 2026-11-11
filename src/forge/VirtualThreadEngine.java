package forge;

import java.net.Socket;

/**
 * Engine 4: one VIRTUAL thread per connection (Java 21).
 *
 * The code looks identical to thread-per-connection, but a virtual thread is a
 * tiny object managed by the JVM, not the OS. When it blocks (sleep, socket read)
 * the JVM parks it and reuses the underlying OS thread for other work. So we get
 * the simple "one thread per client" style with the scalability of an event loop.
 */
final class VirtualThreadEngine extends BlockingEngine {

    VirtualThreadEngine(int port, Router router, Stats stats) { super(port, router, stats); }

    @Override
    protected void dispatch(Socket socket) {
        Thread.ofVirtual().name("forge-vconn").start(() -> serveConnection(socket));
    }

    @Override public String name() { return "virtual"; }
}
