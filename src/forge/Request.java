package forge;

import java.util.Map;

/** A parsed HTTP request. Immutable, so any thread can read it safely. */
public record Request(String method, String path, Map<String, String> query,
                      Map<String, String> headers, boolean keepAlive) {

    public String query(String key, String fallback) {
        return query.getOrDefault(key, fallback);
    }
}
