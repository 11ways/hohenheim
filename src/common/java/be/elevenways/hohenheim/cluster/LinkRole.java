package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What a linked server is to this server: the one vocabulary of {@code linked_servers.role}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum LinkRole {

    /** This server is that server's master: it manages it. */
    MANAGES("manages"),

    /** That server is this server's master. */
    MANAGED_BY("managed_by");

    private final String token;

    LinkRole(@NonNull String token) {
        this.token = token;
    }

    /** @return the stored token */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the role in words, as a list and a filter read it */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.LINKED_SERVER.of("linked_server_role_" + this.token);
    }

    /** @return the member a stored token names, null for a token this build does not declare */
    public static @Nullable LinkRole byToken(@Nullable String token) {
        for (LinkRole role : values()) {
            if (role.token.equals(token)) {
                return role;
            }
        }
        return null;
    }
}
