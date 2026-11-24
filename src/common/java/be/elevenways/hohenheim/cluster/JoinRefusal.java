package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Why a server refused to join: the tokens the join call answers with, worded by the server that asked.
 *
 * AIDEV-NOTE: a wire vocabulary; a token the asking build does not declare reads as {@link #UNREADABLE}, so an older
 * or newer peer's refusal is still a refusal in words.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum JoinRefusal {

    /** No waiting join code has these letters: a typo, or a code already used or replaced. */
    NO_SUCH_CODE("no_such_code"),

    /** The code waited past its life. */
    EXPIRED("expired"),

    /** Too many wrong codes came from this address; the code was not looked at. */
    TOO_MANY_TRIES("too_many_tries"),

    /** That server already has a master. */
    HAS_MASTER("has_master"),

    /** That server manages other servers itself, so it cannot get a master. */
    MANAGES_OTHERS("manages_others"),

    /**
     * Another server is being linked there, or the switch of where its code numbers come from held its lease past the
     * wait; asking again shortly may succeed.
     */
    BUSY("busy"),

    /** The request or its answer could not be read. */
    UNREADABLE("unreadable"),

    /** The server could not take its code numbers from the master, so it did not join and nothing changed. */
    CODES("codes");

    private final String token;

    JoinRefusal(@NonNull String token) {
        this.token = token;
    }

    /** @return the token on the wire */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the refusal in the words the server that asked shows */
    public @NonNull Microcopy message() {
        return HohenheimMicrocopy.VIOLATIONS.of("linked_server_join_" + this.token);
    }

    /** @return the member a token names, {@link #UNREADABLE} for one this build does not declare */
    public static @NonNull JoinRefusal byToken(@NonNull String token) {
        for (JoinRefusal refusal : values()) {
            if (refusal.token.equals(token)) {
                return refusal;
            }
        }
        return UNREADABLE;
    }
}
