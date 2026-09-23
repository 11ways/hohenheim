package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * THE best-effort removal of a controller-owned scratch tree (staging directories, download
 * temp directories).
 *
 * AIDEV-NOTE: the walk never follows a symlink, so a link planted inside a scratch tree is
 * removed as a LINK and its target is untouched. A caller that must KNOW the tree is gone
 * (a row is deleted on the strength of it) needs a reporting variant, not this one.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class FileTrees {

    private FileTrees() {
    }

    /** Delete {@code root} and everything under it, ignoring failures; a null or absent root is a no-op. */
    public static void deleteQuietly(@Nullable Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort scratch cleanup
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // best-effort scratch cleanup
        }
    }
}
