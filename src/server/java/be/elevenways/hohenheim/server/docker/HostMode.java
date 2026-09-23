package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How a Docker host is REACHED: the typed reading of {@link ServerModel#MODE}, with the
 * stored tokens ({@code local}, {@code ssh}) unchanged.
 *
 * AIDEV-NOTE: the mode used to be compared as a string in exactly one place
 * ({@code ServerService.transportFor}), and anything that was not {@code ssh} -- a typo,
 * a null, a mode a later version adds -- silently addressed THIS machine's daemon: a
 * wrong-host operation with no error. {@link #of} now fails closed on anything it does not
 * know. The stored tokens are the ServerModel constants, so rows written by every earlier
 * version keep parsing; {@code HostModeVocabularyDriftTest} binds this enum to the
 * model's EnumField so a value added there without a member here breaks the build.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public enum HostMode {

    /** The controller's own daemon, over its unix socket. */
    LOCAL(ServerModel.MODE_LOCAL, false),

    /** A remote daemon, over the pinned ssh argv of {@code HostKeys.sshArgv}. */
    SSH(ServerModel.MODE_SSH, true);

    private final @NonNull String token;
    private final boolean remote;

    HostMode(@NonNull String token, boolean remote) {
        this.token = token;
        this.remote = remote;
    }

    /** The stored spelling in {@code servers.mode}. */
    public @NonNull String token() {
        return this.token;
    }

    /** Whether reaching this host needs a pinned, operator-confirmed remote identity. */
    public boolean remote() {
        return this.remote;
    }

    /**
     * @throws IllegalArgumentException for a null, blank or unknown token -- never a
     *         default, because the default would be the local daemon
     */
    public static @NonNull HostMode forToken(@Nullable String token) {
        for (HostMode mode : values()) {
            if (mode.token.equals(token)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown Docker host mode '" + token
            + "'; refusing to guess which daemon it means");
    }

    /** The mode a host row declares; fails closed like {@link #forToken}. */
    public static @NonNull HostMode of(@NonNull Row server) {
        try {
            return forToken(server.get(ServerModel.MODE));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Host '" + server.get(ServerModel.NAME) + "': "
                + unknown.getMessage(), unknown);
        }
    }
}
