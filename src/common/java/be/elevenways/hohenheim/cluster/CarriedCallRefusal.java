package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Why a tool call a master carried to a server it manages came back without that server's answer, worded by the
 * master as the tool's answer.
 *
 * AIDEV-NOTE: a wire vocabulary for {@link #answeredByTheServer} members only; a token the master's build does not
 * declare reads as {@link #REFUSED} ({@link #byToken}), so an older or newer server's refusal still says nothing was
 * changed there.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum CarriedCallRefusal {

    /** Nothing reached the server, or a call that changes nothing got no answer. */
    NOT_ANSWERING("not_answering"),

    /** A call that may change something was sent and got no usable answer: it may or may not have been carried out. */
    MAY_HAVE_ARRIVED("may_have_arrived"),

    /** The server refuses this server's key, or another server answers at its address: nothing was sent or done. */
    REFUSED("refused"),

    /** The server runs another version, which does not take this call. */
    OTHER_VERSION("other_version"),

    /** The server holding the thing is no longer linked to this one. */
    GONE("gone"),

    /**
     * The caller is no administrator of this server: until grants follow the master, nobody else may act on a
     * managed server from here. Nothing is sent.
     */
    NOT_ADMINISTRATOR("not_administrator");

    private final String token;

    CarriedCallRefusal(@NonNull String token) {
        this.token = token;
    }

    /** @return the token on the wire */
    public @NonNull String token() {
        return this.token;
    }

    /**
     * @return whether the managed server answers this refusal itself, as its token on the wire: none does since a
     *         server runs a call on its master's word and refuses nobody the master speaks for
     */
    public boolean answeredByTheServer() {
        return switch (this) {
            case NOT_ANSWERING, MAY_HAVE_ARRIVED, REFUSED, OTHER_VERSION, GONE, NOT_ADMINISTRATOR -> false;
        };
    }

    /**
     * @param name the managed server's name, as this server calls it
     * @return the refusal in the words the tool answers with
     */
    public @NonNull Microcopy message(@NonNull String name) {
        return HohenheimMicrocopy.LINKED_SERVER.of("linked_server_tool_" + this.token).withArg("name", name);
    }

    /** @return the member a server's token names, {@link #REFUSED} for one this build does not let a server answer */
    public static @NonNull CarriedCallRefusal byToken(@Nullable Object token) {
        for (CarriedCallRefusal refusal : values()) {
            if (refusal.answeredByTheServer() && refusal.token.equals(token)) {
                return refusal;
            }
        }
        return REFUSED;
    }
}
