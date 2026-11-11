package forge;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts one Forge server.
 *   java -cp out forge.Main --engine=virtual --port=8080 --pool=50 --queue=100 --www=www
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String engineName = opt.getOrDefault("engine", "virtual");
        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));
        int pool = Integer.parseInt(opt.getOrDefault("pool", "50"));
        int queue = Integer.parseInt(opt.getOrDefault("queue", "100"));
        Path www = Path.of(opt.getOrDefault("www", "www"));
        int adminPort = Integer.parseInt(opt.getOrDefault("admin", String.valueOf(port + 1)));

        Engines.Built built = Engines.create(engineName, port, www, pool, queue);
        built.engine().start();
        AdminServer admin = new AdminServer(adminPort, built.stats());
        admin.start();

        // Ctrl+C runs this hook: stop accepting, let the engine release its threads and sockets.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down Forge...");
            admin.stop();
            built.engine().stop();
        }));

        System.out.printf("Forge listening on http://localhost:%d  (engine=%s)%n", port, built.engine().name());
        System.out.printf("Admin stats on  http://localhost:%d/stats (localhost only)%n", adminPort);
        System.out.printf("CPU calibration: %d work units = 1 ms on this machine%n", Router.UNITS_PER_MS);
        System.out.printf("Try:  /hello   /work?ms=50   /cpu?ms=5   /stats   /dashboard%n");
        Thread.currentThread().join();                 // keep main alive; the engine runs on its own threads
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            int eq = a.indexOf('=');
            if (eq < 0) m.put(a.substring(2), "true");
            else m.put(a.substring(2, eq), a.substring(eq + 1));
        }
        return m;
    }
}
