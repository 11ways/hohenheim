package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * THE recursive removal of a controller-owned tree (staging directories, download temp
 * directories, materialized build contexts, snapshot payloads, git checkouts).
 *
 * AIDEV-NOTE: the walk never follows a symlink, so a link planted inside a scratch tree is
 * removed as a LINK and its target is untouched. A caller that must KNOW the tree is gone
 * (a row is deleted on the strength of it) reads {@link #delete}'s answer.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class FileTrees {

    private FileTrees() {
    }

    /** Delete {@code root} and everything under it, ignoring failures; a null or absent root is a no-op. */
    public static void deleteQuietly(@Nullable Path root) {
        try {
            delete(root);
        } catch (RuntimeException ignored) {
            // best-effort scratch cleanup
        }
    }

    /**
     * Delete {@code root} and everything under it, and ANSWER: the first IO error, or null
     * when the tree is gone (a null or absent root included).
     *
     * AIDEV-NOTE: the reporting variant exists because the two lanes of one mechanism once
     * had opposite failure semantics: an unremovable snapshot payload was swallowed and its
     * row deleted anyway, an orphan reported as a successful prune. Anything that deletes a
     * ROW on the strength of the tree being gone must read this answer. Every entry is still
     * attempted after a failure, so a partly removable tree shrinks as far as it can.
     */
    public static @Nullable IOException delete(@Nullable Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        IOException[] first = new IOException[1];
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failed) {
                    if (first[0] == null) {
                        first[0] = failed;
                    }
                }
            });
        } catch (IOException walkFailed) {
            return walkFailed;
        } catch (UncheckedIOException walkFailed) {
            return walkFailed.getCause();
        }
        return first[0];
    }
}
