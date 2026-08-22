package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Site-list TLS cell: whether the site's hostnames force HTTPS.
 *
 * @param token one of {@link #FORCED}/{@link #PARTIAL}/{@link #OFF}/{@link #NONE}
 */
@HawkeyeClass
public record SiteTlsCell(@NonNull String token) {

    /** Every hostname redirects to HTTPS. */
    public static final String FORCED = "forced";

    /** Some hostnames force HTTPS, some do not. */
    public static final String PARTIAL = "partial";

    /** No hostname forces HTTPS. */
    public static final String OFF = "off";

    /** The site has no hostnames yet. */
    public static final String NONE = "none";

    /** The pl-badge variant for this state (derived, so it never crosses the wire). */
    public @NonNull String variant() {
        return switch (this.token) {
            case FORCED -> "success";
            case PARTIAL -> "warning";
            case OFF -> "secondary";
            default -> "outline";
        };
    }

    /** The translated wording for this state. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "site_tls");
    }
}
