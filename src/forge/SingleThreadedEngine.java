package forge;

import java.net.Socket;

/**
 * Engine 1: one thread does everything, one client at a time.
 * Keep-alive is OFF here: if one client kept its connection open, nobody else
 * could ever be served. Even so, a slow request blocks every other client.
 */
final class SingleThreadedEngine extends BlockingEngine {

    SingleThreadedEngine(int port, Router router, Stats stats) { super(port, router, stats); }

    @Override protected void dispatch(Socket socket) { serveConnection(socket); }   // runs on the accept thread

    @Override protected boolean allowKeepAlive() { return false; }

    @Override public String name() { return "single"; }
}
