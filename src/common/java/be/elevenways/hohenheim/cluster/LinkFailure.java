package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Why the last call to a linked server failed: the one vocabulary of {@code linked_servers.last_failure}.
 *
 * AIDEV-NOTE: a stored token this build does not declare reads as {@link #BAD_ANSWER} ({@link #byToken}), so an
 * unknown failure is still a failure and never a reachable server.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum LinkFailure {

    /** Nothing was sent: the address did not resolve, refused the connection or its certificate. */
    UNREACHABLE("unreachable"),

    /** The request was sent and no answer came back in time. */
    SILENT("silent"),

    /** It answered, refusing the key this server presents. */
    REFUSED("refused"),

    /** It answered something that is no status. */
    BAD_ANSWER("bad_answer"),

    /** It answered with another controller identity than the server that joined: another database answers there. */
    OTHER_SERVER("other_server");

    private final String token;

    LinkFailure(@NonNull String token) {
        this.token = token;
    }

    /** @return the stored token */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the failure in words, the line under a linked server's state */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.LINKED_SERVER.of("linked_server_failure_" + this.token);
    }

    /** @return whether nothing more is sent to that address until an administrator joins again */
    public boolean stopsSending() {
        return switch (this) {
            case OTHER_SERVER -> true;
            case UNREACHABLE, SILENT, REFUSED, BAD_ANSWER -> false;
        };
    }

    /** @return the member a stored token names; null for none, {@link #BAD_ANSWER} for an undeclared token */
    public static @Nullable LinkFailure byToken(@Nullable String token) {
        if (token == null) {
            return null;
        }
        for (LinkFailure failure : values()) {
            if (failure.token.equals(token)) {
                return failure;
            }
        }
        return BAD_ANSWER;
    }
}
