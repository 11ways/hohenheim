package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Whether an address points at this proxy, in a word: the Addresses list's "Points here" cell and the address lead.
 *
 * AIDEV-NOTE: the lookup's own verdict ({@link HostnameReach.Verdict}) is one source; {@link #PER_NAME} is the state
 * of a pattern no single lookup answers for, which the lookup never returns.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
enum AddressReach implements WordedState {

    /** A pattern: each name it catches needs its own record. */
    PER_NAME("per_name", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_per_name")),

    /** The lookup is still running. */
    CHECKING("checking", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_checking")),

    /** No lookup answered and none runs: every lookup place was taken. */
    NOT_CHECKED("not_checked", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_not_checked")),

    /** The name resolves to this proxy. */
    POINTS_HERE("points_here", BadgeVariant.SUCCESS, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_yes")),

    /** The name resolves somewhere else. */
    POINTS_ELSEWHERE("points_elsewhere", BadgeVariant.WARNING, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_no")),

    /** The name does not resolve at all. */
    UNRESOLVED("unresolved", BadgeVariant.WARNING, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_unresolved")),

    /** The name resolves elsewhere, but this host sits behind a private address and declares no public one. */
    UNKNOWN("unknown", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("points_here_unknown"));

    private final String token;
    private final BadgeVariant variant;
    private final Microcopy label;

    AddressReach(@NonNull String token, @NonNull BadgeVariant variant, @NonNull Microcopy label) {
        this.token = token;
        this.variant = variant;
        this.label = label;
    }

    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    @Override
    public @NonNull Microcopy label() {
        return this.label;
    }
}
