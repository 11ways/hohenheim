package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Whether HTTPS WORKS for a site's names, never whether it is forced: the Sites list's HTTPS cell.
 *
 * AIDEV-NOTE: "forced" used to be the green badge, so a site forcing HTTPS on a name without a certificate (every
 * visitor on an error page) read healthy. The state is derived from the names' certificate coverage (CertCoverage);
 * {@link #BROKEN} is the one that names the error page. {@link #MISSING} says "No certificate" in the outline variant
 * CertCoverage's NONE wears for the same words (it was secondary).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum SiteTls implements WordedState {

    /** An active certificate covers every exact name. */
    WORKS("works", BadgeVariant.SUCCESS, HohenheimMicrocopy.SITE_TLS.of("works")),

    /** Some exact names have a working certificate, some do not. */
    PARTIAL("partial", BadgeVariant.WARNING, HohenheimMicrocopy.SITE_TLS.of("partial")),

    /** No exact name has a working certificate yet; visitors are served plain HTTP. */
    MISSING("missing", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_TLS.of("missing")),

    /** A name forces HTTPS without a working certificate: its visitors get an error page. */
    BROKEN("broken", BadgeVariant.DESTRUCTIVE, HohenheimMicrocopy.SITE_TLS.of("broken")),

    /** Every name is a pattern, which no one certificate answers for. */
    PATTERNS("patterns", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_TLS.of("patterns")),

    /** A TLS passthrough site: HTTPS is the backend's, not this proxy's. */
    NOT_USED("not_used", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_TLS.of("not_used")),

    /** The site has no hostnames yet. */
    NONE("none", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_TLS.of("none"));

    private final String token;
    private final BadgeVariant variant;
    private final Microcopy label;

    SiteTls(@NonNull String token, @NonNull BadgeVariant variant, @NonNull Microcopy label) {
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
