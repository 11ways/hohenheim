package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE parent and last segment of a slash-separated path on a host or in a workload, never through the JVM's own
 * filesystem; one trailing slash is not a segment.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class PosixPaths {

    private PosixPaths() {
    }

    /** @return the parent directory, {@code /} for a top-level path or a bare name */
    public static @NonNull String parentOf(@NonNull String path) {
        String trimmed = withoutTrailingSlash(path);
        int slash = trimmed.lastIndexOf('/');
        return slash <= 0 ? "/" : trimmed.substring(0, slash);
    }

    /** @return the last segment, the whole value for a bare name, empty for {@code /} */
    public static @NonNull String nameOf(@NonNull String path) {
        String trimmed = withoutTrailingSlash(path);
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static @NonNull String withoutTrailingSlash(@NonNull String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
