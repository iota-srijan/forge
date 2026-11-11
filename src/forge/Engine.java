package forge;

import java.io.IOException;

/** One threading strategy for serving connections. Same HTTP code, different concurrency. */
public interface Engine {
    void start() throws IOException;
    void stop();
    String name();
}
