package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * THE reading of a git source's declared build detail -- {@code build_directory},
 * {@code build_timeout} and {@code build_environment_variables} -- for every lane that runs
 * a build.
 *
 * AIDEV-NOTE: these three fields were declared on the source (GitSourceSchema.BUILD_DETAIL),
 * rendered in the form and stored, and read by NOTHING: an operator who set a build timeout
 * or a build directory got the defaults with no hint that the value was ignored. They are
 * honoured through here, by their own help text: the directory is the build COMMAND's
 * working directory (the workspace lane; a Dockerfile build has no build command), the
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
                Microcopy.of("source_build_directory_invalid").withFilter("scope", "violations"));
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
        String text = declared == null ? "" : declared.toString().trim();
        return text.isEmpty() || text.equals(".") ? null : text;
    }
}
