package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.util.PosixPaths;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.content.FilePaths;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The LEXICAL half of file-manager containment: a caller-supplied path is REFUSED unless
 * it is already the one canonical spelling of a path under one of the instance's declared
 * volume roots. Nothing here is normalized, rewritten or cleaned up -- normalization is
 * how {@code /vol/../etc/passwd} becomes {@code /etc/passwd} and gets served with a green
 * test beside it.
 *
 * AIDEV-NOTE: refusing rather than normalizing is what makes the ENCODED variants
 * ({@code %2e%2e}, {@code %2f}) harmless without knowing about them: the HTTP layer
 * percent-decodes exactly once before a handler sees a value, so {@code %2e%2e} arrives
 * as the segment {@code ".."} and hits the segment rule, while a doubly-encoded
 * {@code %252e%252e} arrives as the literal text {@code "%2e%2e"} -- an ordinary,
 * containment-passing FILENAME that simply does not exist. There is no decode in this
 * class precisely so there can be no second decode.
 *
 * This is only half the containment; {@link InstanceFiles} walks every ancestor component
 * through the driver's lstat to prove no component is a SYMLINK, because the daemon's
 * archive API happily resolves {@code /data/link/passwd} for a link named {@code /data/link}
 * and reports the RESULT as an ordinary file.
 */
public record InstanceFilePath(@NonNull String volumeRoot, @NonNull String absolute) {

    /** How deep a path may go below its volume root; bounds the per-component stat walk. */
    public static final int MAX_DEPTH = 32;

    /** Longest accepted absolute path (Linux PATH_MAX is 4096). */
    public static final int MAX_LENGTH = 1024;

    /**
     * Parse a caller-supplied absolute container path against the instance's declared
     * volume roots.
     *
     * @param volumeRoots the container paths the instance's own named volumes mount at
     * @throws Violations {@code files_path_refused} for anything that is not the one
     *         canonical spelling of a path inside one of those roots
     */
    public static @NonNull InstanceFilePath parse(@NonNull Collection<String> volumeRoots,
                                                  @NonNull String submitted) {
        if (submitted.isEmpty() || submitted.length() > MAX_LENGTH || submitted.charAt(0) != '/') {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
        }
        // Control characters (NUL included) never appear in a path we author, and a NUL
        // is how a C-level consumer downstream can be made to see a shorter string.
        for (int i = 0; i < submitted.length(); i++) {
            if (submitted.charAt(i) < 0x20 || submitted.charAt(i) == 0x7F) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
            }
        }

        List<String> segments = segmentsOf(submitted);
        // Canonical-spelling test: the path REBUILT from its accepted segments must be
        // the submitted string byte for byte, so "//a", "/a/", "/a/./b" and "/a/../b"
        // are all refusals rather than aliases of an accepted path.
        StringBuilder rebuilt = new StringBuilder();
        for (String segment : segments) {
            rebuilt.append('/').append(segment);
        }
        if (!rebuilt.toString().equals(submitted)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
        }

        String root = rootFor(volumeRoots, submitted);
        if (root == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
        }
        if (segments.size() - segmentsOf(root).size() > MAX_DEPTH) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
        }
        return new InstanceFilePath(root, submitted);
    }

    /** The canonical, de-duplicated volume roots of a spec's volume map (deepest first). */
    public static @NonNull Set<String> rootsOf(@NonNull Collection<String> declared) {
        Set<String> roots = new LinkedHashSet<>();
        for (String path : declared) {
            if (path == null || path.isEmpty() || path.charAt(0) != '/') {
                continue;
            }
            String trimmed = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
            // A volume mounted AT the container root would make the whole image
            // filesystem "the tenant's volume"; that is not a browse root.
            if ("/".equals(trimmed)) {
                continue;
            }
            roots.add(trimmed);
        }
        return roots;
    }

    /** @return the accepted root covering {@code path}, or null when none does */
    private static String rootFor(@NonNull Collection<String> volumeRoots, @NonNull String path) {
        for (String root : rootsOf(volumeRoots)) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return root;
            }
        }
        return null;
    }

    /** Split on '/', skipping empty segments and refusing "." and ".." outright (FilePaths.segments). */
    private static @NonNull List<String> segmentsOf(@NonNull String path) {
        List<String> segments = FilePaths.segments(path);
        if (segments == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
        }
        return segments;
    }

    /** Whether this path IS a volume root (which can never be renamed or deleted). */
    public boolean isVolumeRoot() {
        return this.absolute.equals(this.volumeRoot);
    }

    /** The last segment; the volume root's own basename for a root path. */
    public @NonNull String name() {
        return PosixPaths.nameOf(this.absolute);
    }

    /**
     * @return the parent path, or null when this IS the volume root
     */
    public InstanceFilePath parent() {
        if (this.isVolumeRoot()) {
            return null;
        }
        return new InstanceFilePath(this.volumeRoot, PosixPaths.parentOf(this.absolute));
    }

    /**
     * Every path from the volume root down to (and including) this one -- the exact
     * component list the symlink walk must lstat.
     */
    public @NonNull List<InstanceFilePath> chainFromRoot() {
        List<InstanceFilePath> chain = new ArrayList<>();
        for (InstanceFilePath current = this; current != null; current = current.parent()) {
            chain.add(0, current);
        }
        return chain;
    }

    /** A child of this path, parsed under the SAME rules as any submitted path. */
    public @NonNull InstanceFilePath child(@NonNull Collection<String> volumeRoots,
                                           @NonNull String name) {
        String base = this.absolute.endsWith("/") ? this.absolute : this.absolute + "/";
        return parse(volumeRoots, base + name);
    }
}
