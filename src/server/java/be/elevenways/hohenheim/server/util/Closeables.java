package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;

/**
 * Best-effort closing for teardown and failure paths, where a second IO error has nothing left to report.
 *
 * AIDEV-NOTE: belongs in protoblast; protoblast and zenit hold private copies of their own
 * (RunningProcess, SqlDatasource, SqliteDatasource, PinnedFetcher).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class Closeables {

    private Closeables() {
    }

    /** Closes {@code closeable}, ignoring a null and any IO error. */
    public static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
