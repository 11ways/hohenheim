package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * THE reading of a git source's declared build detail -- {@code build_directory},
 * {@code build_timeout} and {@code build_environment_variables} -- for every lane that runs
 * a build.
 *
 * AIDEV-NOTE: these three fields were declared on the source (GitSourceSchema.BUILD_DETAIL),
 * rendered in the form and stored, and read by NOTHING: an operator who set a build timeout
 * or a build directory got the defaults with no hint that the value was ignored. They are
 * honoured through here, by their own help text: the directory is the build COMMAND's
 * working directory (the workspace and static-site lanes) or a Dockerfile or buildpack build's context
 * ({@link #buildContext}, the application lane, which runs no build command), the
 * environment exists only while that command runs, and the timeout can only SHORTEN a build
 * -- the host's build quota still caps it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SourceBuildDetail {

    private SourceBuildDetail() {
    }

    /**
     * The build command's working directory under {@code checkoutRoot}.
     *
     * @return the root itself when the source declares none
     * @throws Violations {@code source_build_directory_invalid} for an absolute path or one
     *         that climbs out of the checkout
     */
    public static @NonNull String workingDirectory(@NonNull Map<String, Object> settings,
                                                   @NonNull String checkoutRoot) {
        String declared = declaredDirectory(settings);
        if (declared == null) {
            return checkoutRoot;
        }
        Path root = Path.of(checkoutRoot).normalize();
        Path resolved;
        try {
            resolved = Path.of(declared).isAbsolute() ? null : root.resolve(declared).normalize();
        } catch (InvalidPathException unparseable) {
            resolved = null;
        }
        if (resolved == null || !resolved.startsWith(root)) {
            throw Violations.ofField(GitSourceSchema.BUILD_DIRECTORY, declared,
                HohenheimMicrocopy.VIOLATIONS.of("source_build_directory_invalid"));
        }
        return resolved.toString();
    }

    /**
     * Refuse a declared build directory that could not be a subdirectory of any checkout;
     * the write-time face of {@link #workingDirectory}.
     *
     * @throws Violations {@code source_build_directory_invalid}
     */
    public static void requireContainedDirectory(@NonNull Map<String, Object> settings) {
        workingDirectory(settings, "/checkout");
    }

    /**
     * The context a release or preview build of a checkout reads: the declared build folder for a Dockerfile or
     * buildpack build, the checkout itself for a static site, whose builder runs its build command in that folder.
     *
     * AIDEV-NOTE: the folder's REAL path is checked against the checkout's, so a symlink in the repository cannot
     * hand the builder a context outside it; the textual check of {@link #workingDirectory} alone cannot see that.
     *
     * @throws Violations {@code source_build_directory_invalid} for a folder outside the checkout, a symlink leaving
     *         it, or a path that is not an existing folder
     */
    public static @NonNull Path buildContext(@NonNull Map<String, Object> settings, @NonNull Path checkoutRoot) {
        if (BuildOperationModel.KIND_STATIC.equals(BuildOperationModel.kindOrDefault(settings.get("builder")))
                || declaredDirectory(settings) == null) {
            return checkoutRoot;
        }
        Path folder = Path.of(workingDirectory(settings, checkoutRoot.toString()));
        try {
            Path real = folder.toRealPath();
            if (Files.isDirectory(real) && real.startsWith(checkoutRoot.toRealPath())) {
                return folder;
            }
        } catch (IOException unresolvable) {
            // absent or unreadable: refused below like any other folder that is not inside the checkout
        }
        throw Violations.ofField(GitSourceSchema.BUILD_DIRECTORY,
            trimmed(settings.get(GitSourceSchema.BUILD_DIRECTORY)),
            HohenheimMicrocopy.VIOLATIONS.of("source_build_directory_invalid"));
    }

    /**
     * The build's time budget: the source's declared {@code build_timeout} when it is
     * shorter than the host's cap, the cap otherwise (an unset or non-positive value
     * included).
     */
    public static long timeoutMs(@NonNull Map<String, Object> settings, long hostCapMs) {
        Object declared = settings.get(GitSourceSchema.BUILD_TIMEOUT);
        if (declared instanceof Number seconds && seconds.longValue() > 0) {
            return Math.min(hostCapMs, seconds.longValue() * 1000L);
        }
        return hostCapMs;
    }

    /** The environment the build command runs with, and only the build command. */
    public static @NonNull Map<String, String> environment(@NonNull Map<String, Object> settings) {
        return EnvVars.toMap(settings.get(GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES));
    }

    private static @Nullable String declaredDirectory(@NonNull Map<String, Object> settings) {
        Object declared = settings.get(GitSourceSchema.BUILD_DIRECTORY);
        String text = trimmed(declared);
        return text.isEmpty() || text.equals(".") ? null : text;
    }
}
