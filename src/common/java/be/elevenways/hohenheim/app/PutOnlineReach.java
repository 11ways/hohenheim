package be.elevenways.hohenheim.app;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How what is put online is reached from outside, which decides the steps "Put something online" asks: a website an
 * address and HTTPS, a service its published ports, a machine neither.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PutOnlineReach {

    /** Visitors type an address; Hohenheim's proxy serves it, with a certificate. */
    WEBSITE,

    /** Clients connect to ports published on the host (a game server, any service that does not speak HTTP). */
    PORTS,

    /** A machine reached on its own network; neither an address nor ports are asked when it is put online. */
    NONE;

    /** Whether putting it online asks for an address and its HTTPS. */
    public boolean asksAddress() {
        return switch (this) {
            case WEBSITE -> true;
            case PORTS, NONE -> false;
        };
    }

    /** Whether putting it online asks which host ports its declared ports are published on. */
    public boolean asksPorts() {
        return switch (this) {
            case PORTS -> true;
            case WEBSITE, NONE -> false;
        };
    }

    /**
     * @return the help under the address a flow reaching it this way asks, null when it asks none
     *
     * AIDEV-NOTE: the help is the reach's, never one text for every flow: the address once said "leave it empty for
     * an app nobody reaches by name, like a game server" on a static site, long after game servers moved to their
     * Ports step and stopped being asked an address at all.
     */
    public @Nullable Microcopy addressHelp() {
        return switch (this) {
            case WEBSITE -> HohenheimMicrocopy.PUT_ONLINE.of("address_help");
            case PORTS, NONE -> null;
        };
    }

    /**
     * @return the help under the access list a site reached this way follows: a site on its own ports (a stream) is
     *         forwarded unread, so only the list's address rules apply to it
     */
    public @NonNull Microcopy accessListHelp() {
        return switch (this) {
            case WEBSITE, NONE -> HohenheimMicrocopy.HELP.of("access_list");
            case PORTS -> HohenheimMicrocopy.HELP.of("stream_access_list");
        };
    }

    /** @return the reach as a route names it */
    public @NonNull String token() {
        return switch (this) {
            case WEBSITE -> "website";
            case PORTS -> "ports";
            case NONE -> "none";
        };
    }

    /** @return the reach a route names, null for a token this vocabulary does not know */
    public static @Nullable PutOnlineReach ofToken(@Nullable String token) {
        for (PutOnlineReach reach : values()) {
            if (reach.token().equals(token)) {
                return reach;
            }
        }
        return null;
    }

    /**
     * @return the chooser card's label for a kind offered this way beside its own card, the kind's label as
     *         {@code kind}
     */
    public @NonNull Microcopy cardLabel() {
        return HohenheimMicrocopy.PUT_ONLINE.of("reach_card_" + this.token());
    }

    /** @return what that card says the kind is, put online this way */
    public @NonNull Microcopy cardDescription() {
        return HohenheimMicrocopy.PUT_ONLINE.of("reach_card_" + this.token() + "_help");
    }
}
