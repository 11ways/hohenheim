package be.elevenways.hohenheim.server.source;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one remote-touching git command authenticates with, and what goes away once it ran: the environment git reads
 * the credential from, never its command line or the repository's config.
 *
 * AIDEV-NOTE: a credential that is a FILE (an SSH key) exists for the one command that needs it; {@link #close}
 * removes it. Such an access is not {@link #portable()}: its environment names a path on the controller, so a lane
 * that runs git elsewhere (a workspace's own container) cannot use it and must refuse rather than ship the file.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface GitAccess extends AutoCloseable {

    /** No credential: an anonymous clone. */
    GitAccess NONE = of(Map.of());

    /** @return the environment the git process authenticates through */
    @NonNull Map<String, String> environment();

    /** @return whether the environment works in another process namespace, which a file on the controller does not */
    default boolean portable() {
        return true;
    }

    /** Removes what the access put on disk; nothing for an environment-only one. */
    @Override
    default void close() {
    }

    /** @return an environment-only access */
    static @NonNull GitAccess of(@NonNull Map<String, String> environment) {
        Map<String, String> copy = Map.copyOf(environment);
        return () -> copy;
    }

    /**
     * The HTTP Basic credential of a token, as an {@code http.<origin>.extraHeader} through git's environment config:
     * never on the command line (visible in /proc), never in the URL (GitRepository refuses embedded user-info).
     *
     * @param cloneUrl the https URL the credential is scoped to, by its origin
     */
    static @NonNull GitAccess httpBasic(@NonNull String cloneUrl, @NonNull String username, @NonNull String secret) {
        String basic = Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "http." + originOf(cloneUrl) + "/.extraHeader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        env.put("GIT_TERMINAL_PROMPT", "0");
        return of(env);
    }

    /** Scheme and authority of a URL: the config key a credential binds to. */
    private static @NonNull String originOf(@NonNull String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }
}
