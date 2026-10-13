package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What a published port is for, read off the name a template gives it (the Pterodactyl allocation's role).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PortPurpose {

    /** The port players connect to. */
    GAME,

    /** A server-list or status query port. */
    QUERY,

    /** Remote console access. */
    RCON,

    /** A voice chat port. */
    VOICE,

    /** Any name this vocabulary does not know. */
    OTHER;

    /** @return the port name a template uses for this purpose */
    public @NonNull String token() {
        return switch (this) {
            case GAME -> "game";
            case QUERY -> "query";
            case RCON -> "rcon";
            case VOICE -> "voice";
            case OTHER -> "other";
        };
    }

    /** @return the purpose a port name declares; an unknown name is {@link #OTHER}, which carries no SRV record */
    public static @NonNull PortPurpose forName(@Nullable String name) {
        for (PortPurpose purpose : values()) {
            if (purpose.token().equals(name)) {
                return purpose;
            }
        }
        return OTHER;
    }

    /** @return the purpose in words */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.PORT_PURPOSE.of("purpose_" + this.token());
    }

    /** Whether a game address's SRV record points at this port. */
    public boolean carriesSrv() {
        return switch (this) {
            case GAME -> true;
            case QUERY, RCON, VOICE, OTHER -> false;
        };
    }
}
