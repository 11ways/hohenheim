package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE Docker host mode vocabulary: how a Docker host is REACHED, one member per stored
 * {@code servers.mode} token, with the facts the rest of the code reads off the member.
 *
 * AIDEV-NOTE: {@link ServerModel#MODE} is BUILT from this enum, so the stored field and
 * the typed reading cannot drift apart. The tokens stay the {@code ServerModel.MODE_*}
 * string constants (the InstanceStatus binding), so rows written by every earlier version
 * keep parsing. The mode used to be compared as a string, and anything that was not
 * {@code ssh} -- a typo, a null, a mode a later version adds -- silently addressed THIS
 * machine's daemon: a wrong-host operation with no error. {@link #forToken} fails closed
 * on anything it does not know, and {@link #parse} answers null so a caller that must not
 * throw decides its own fail-closed reading. {@code HostModeVocabularyDriftTest} binds the
 * two.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public enum HostMode {

    /** The controller's own daemon, over its unix socket. */
    LOCAL(ServerModel.MODE_LOCAL, false, "Local", "house", "teal"),

    /** A remote daemon, over the pinned ssh argv of {@code HostKeys.sshArgv}. */
    SSH(ServerModel.MODE_SSH, true, "SSH", "terminal", "indigo");

    private final @NonNull String token;
    private final boolean remote;
    private final @NonNull String displayName;
    private final @NonNull String icon;
    private final @NonNull String color;

    HostMode(@NonNull String token, boolean remote, @NonNull String displayName,
             @NonNull String icon, @NonNull String color) {
        this.token = token;
        this.remote = remote;
        this.displayName = displayName;
        this.icon = icon;
        this.color = color;
    }

    /** The stored spelling in {@code servers.mode}. */
    public @NonNull String token() {
        return this.token;
    }

    /** Whether reaching this host needs a pinned, operator-confirmed remote identity. */
    public boolean remote() {
        return this.remote;
    }

    /** The English display name the mode field declares beside its label. */
    public @NonNull String displayName() {
        return this.displayName;
    }

    /** The badge icon name. */
    public @NonNull String icon() {
        return this.icon;
    }

    /** The badge color. */
    public @NonNull String color() {
        return this.color;
    }

    /** The translatable label, keyed by the token under the {@code host_mode} scope. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "host_mode");
    }

    /** The member a stored token names, or null for a null, blank or unknown token. */
    public static @Nullable HostMode parse(@Nullable String token) {
        for (HostMode mode : values()) {
            if (mode.token.equals(token)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * @throws IllegalArgumentException for a null, blank or unknown token -- never a
     *         default, because the default would be the local daemon
     */
    public static @NonNull HostMode forToken(@Nullable String token) {
        HostMode mode = parse(token);
        if (mode == null) {
            throw new IllegalArgumentException("Unknown Docker host mode '" + token
                + "'; refusing to guess which daemon it means");
        }
        return mode;
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

    /** Whether a host row declares exactly this mode; an unknown or missing mode is no mode. */
    public boolean declaredBy(@Nullable Row server) {
        return server != null && parse(server.get(ServerModel.MODE)) == this;
    }
}
