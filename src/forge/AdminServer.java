package forge;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A tiny separate server that only answers GET /stats, on its own port and threads.
 *
 * Why: when the main engine is overloaded (exactly when measurements matter most),
 * a /stats request on the main port waits in the same queue as everyone else, or
 * gets a 503. Production servers solve this with a separate management port
 * (Spring Boot Actuator, Envoy's admin port). Same idea here.
 * It binds to localhost only, so it is not reachable from other machines.
 */
final class AdminServer {

    private final int port;
    private final Stats stats;
    private ServerSocket socket;
    private volatile boolean running;

    AdminServer(int port, Stats stats) {
        this.port = port;
        this.stats = stats;
    }

    void start() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        running = true;
        // One dedicated OS thread at top priority, serving requests inline.
        // Not a virtual thread: when every core is saturated by the engine under test,
        // virtual threads wait for a free carrier and the measurements would go blind.
        Thread t = new Thread(this::acceptLoop, "forge-admin");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                serve(socket.accept());         // stats are tiny: answering inline is fine
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setSoTimeout(2000);
            String head = Http.readHead(new BufferedInputStream(s.getInputStream()));
            if (head == null) return;
            Request req = Http.parseHead(head);
            Response resp = req.path().equals("/stats")
                    ? Response.json(stats.json())
                    : Response.text(404, "admin port only serves /stats\n");
            s.getOutputStream().write(Http.serialize(resp, false, false));
        } catch (IOException ignored) {
        }
    }

    void stop() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
