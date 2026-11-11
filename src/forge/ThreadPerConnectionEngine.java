package forge;

import java.net.Socket;

/**
 * Engine 2: a brand new OS thread for every connection (the video's "multithreaded" stage).
 * Simple and fast to write, but each platform thread reserves ~1 MB of stack and
 * the OS has to schedule thousands of them. There is no upper limit here on purpose:
 * that is the weakness the benchmark exposes.
 */
final class ThreadPerConnectionEngine extends BlockingEngine {

    ThreadPerConnectionEngine(int port, Router router, Stats stats) { super(port, router, stats); }

    @Override
    protected void dispatch(Socket socket) {
        Thread t = new Thread(() -> serveConnection(socket), "forge-conn");
        t.start();
    }

    @Override public String name() { return "thread"; }
}
