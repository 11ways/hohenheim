package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Site-list HTTPS cell: whether HTTPS WORKS for the site's names, never whether it is forced.
 *
 * AIDEV-NOTE: "forced" used to be the green badge, so a site forcing HTTPS on a name without a certificate (every
 * visitor on an error page) read healthy. The state is derived from the names' certificate coverage (CertCoverage);
 * {@link #BROKEN} is the one that names the error page.
 *
 * @param token one of the constants below
 */
@HawkeyeClass
public record SiteTlsCell(@NonNull String token) {

    /** An active certificate covers every exact name. */
    public static final String WORKS = "works";

    /** Some exact names have a working certificate, some do not. */
    public static final String PARTIAL = "partial";

    /** No exact name has a working certificate yet; visitors are served plain HTTP. */
    public static final String MISSING = "missing";

    /** A name forces HTTPS without a working certificate: its visitors get an error page. */
    public static final String BROKEN = "broken";

    /** Every name is a pattern, which no one certificate answers for. */
    public static final String PATTERNS = "patterns";

    /** A TLS passthrough site: HTTPS is the backend's, not this proxy's. */
    public static final String NOT_USED = "not_used";

    /** The site has no hostnames yet. */
    public static final String NONE = "none";

    /** The pl-badge variant for this state (derived, so it never crosses the wire). */
    public @NonNull BadgeVariant variant() {
        return switch (this.token) {
            case WORKS -> BadgeVariant.SUCCESS;
            case PARTIAL -> BadgeVariant.WARNING;
            case MISSING -> BadgeVariant.SECONDARY;
            case BROKEN -> BadgeVariant.DESTRUCTIVE;
            default -> BadgeVariant.OUTLINE;
        };
    }

    /** The translated wording for this state. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "site_tls");
    }
}
