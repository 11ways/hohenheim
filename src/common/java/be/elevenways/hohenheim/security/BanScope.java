package be.elevenways.hohenheim.security;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/**
 * WHICH traffic a ban refuses: THE declaring home of that vocabulary, so the stored
 * token, the enum label, the nftables set choice and the event-type mapping can never
 * be three different lists.
 *
 * AIDEV-NOTE: two scopes exist because there are two nftables sets, and there are two
 * sets because {@code security.nftables_ports} forbids widening the web rule to port 22
 * (the unscoped-fail2ban-jail incident). A web ban must never silently start refusing
 * SSH, and an SSH brute-forcer must never be dropped off a customer's website by a
 * rule an operator scoped to 80/443. The scope is therefore a FACT on the ban row,
 * decided once at creation, and every enforcement tier switches on it exhaustively.
 *
 * @author Jelle De Loecker
 * @since  0.3.0
 */
public enum BanScope {

    /** HTTP/TLS traffic to the proxy: the app-level ban cache plus the web nftables sets. */
    WEB("web"),

    /** SSH traffic only: the ssh nftables sets, and deliberately NOT the proxy's ban cache. */
    SSH("ssh");

    /**
     * The event types whose crossing means the actor was attacking SSH, derived from core's
     * own constants rather than re-spelled here.
     */
    private static final Set<String> SSH_EVENT_TYPES = Set.of(
        SecurityEventTypes.SSH_INVALID_USER,
        SecurityEventTypes.SSH_PASSWORD_FAILED,
        SecurityEventTypes.SSH_PUBLICKEY_FAILED,
        SecurityEventTypes.SSH_PREAUTH_ABORT,
        SecurityEventTypes.SSH_MAX_ATTEMPTS,
        SecurityEventTypes.SSH_PROTOCOL_ABUSE);

    private final String token;

    BanScope(String token) {
        this.token = token;
    }

    /** The stored column value. */
    public @NonNull String token() {
        return this.token;
    }

    /** The localized label an operator reads. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "ban_scope");
    }

    /** Every token, in declaration order, for surfaces that enumerate the vocabulary. */
    public static @NonNull List<String> tokens() {
        return List.of(WEB.token, SSH.token);
    }

    /**
     * The scope of a stored token.
     *
     * @return the scope, or null when the token is neither absent nor one this build knows --
     *         enforcement then programs the row NOWHERE and says so, because there is no
     *         safe superset to guess (a null/blank token is a pre-scope row and is WEB)
     */
    public static @Nullable BanScope fromToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return WEB;
        }
        String trimmed = token.trim();
        for (BanScope scope : values()) {
            if (scope.token.equals(trimmed)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * The scope an automatic ban triggered by this event type belongs in.
     *
     * AIDEV-NOTE: the CROSSING event decides, and one actor doing both hostname scanning
     * and SSH brute force therefore lands in whichever scope its threshold-crossing event
     * came from. That is deliberate: the scorer keeps ONE score per actor (the whole point
     * of weighted scoring across signals), and splitting it per scope would make each half
     * take twice as long to trip.
     *
     * @return SSH for the sshd family, WEB for everything else including a null type
     */
    public static @NonNull BanScope forEventType(@Nullable String eventType) {
        return eventType != null && SSH_EVENT_TYPES.contains(eventType) ? SSH : WEB;
    }
}
