package forge;

import java.nio.file.Path;

/** Builds an engine by name, wired to its own fresh Stats and Router. */
public final class Engines {

    public static final String[] ALL = {"single", "thread", "pool", "virtual", "nio1", "nio"};

    public record Built(Engine engine, Stats stats) {}

    private Engines() {}

    public static Built create(String name, int port, Path www, int poolSize, int queueSize) {
        Stats stats = new Stats();
        Router router = new Router(stats, www);
        Engine engine = switch (name) {
            case "single"  -> new SingleThreadedEngine(port, router, stats);
            case "thread"  -> new ThreadPerConnectionEngine(port, router, stats);
            case "pool"    -> new PoolEngine(port, router, stats, poolSize, queueSize);
            case "virtual" -> new VirtualThreadEngine(port, router, stats);
            case "nio1"    -> new NioEngine(port, router, stats, 1);
            case "nio"     -> new NioEngine(port, router, stats, Runtime.getRuntime().availableProcessors());
            default -> throw new IllegalArgumentException(
                    "unknown engine '" + name + "' (choose: single, thread, pool, virtual, nio1, nio)");
        };
        return new Built(engine, stats);
    }
}
