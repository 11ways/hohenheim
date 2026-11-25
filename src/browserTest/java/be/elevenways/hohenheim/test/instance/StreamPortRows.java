package be.elevenways.hohenheim.test.instance;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stream's public ports as the panel's ports table posts them: one indexed {@code {key, value}} row per port.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class StreamPortRows {

    private StreamPortRows() {
    }

    /** @return the rows of these ports, keyed by public port, valued by their spelling ({@code 2525/tcp}) */
    static @NonNull Map<String, Object> of(@NonNull Map<String, String> ports) {
        Map<String, Object> rows = new LinkedHashMap<>();
        ports.forEach((listen, spec) -> rows.put(String.valueOf(rows.size()), Map.of("key", listen, "value", spec)));
        return rows;
    }

    /** @return the row of one port */
    static @NonNull Map<String, Object> of(int listen, @NonNull String spec) {
        return of(Map.of(String.valueOf(listen), spec));
    }
}
