package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Whether HTTPS works for one exact hostname: which certificate state covers it, the vocabulary every HTTPS cell
 * (a domain row, a site's summary) renders.
 *
 * AIDEV-NOTE: the three covered states DERIVE their key from CertificateModel's own STATUS values
 * (the declaring home); NONE is the one state that model cannot express. How a state renders -- the
 * {@code data-cert-status} key, the badge variant and the wording a reader who may not open the
 * certificate sees -- is a fact on the member, so the domains tab compares no literal. An unknown
 * certificate status fails CLOSED onto {@link #ERROR}: a coverage badge never claims coverage it
 * cannot vouch for. NOT_USED is the second state without a certificate, for a name this proxy terminates no TLS for;
 * PATTERN the third, for an address that is a pattern. DashboardVocabularyDriftTest binds the members to the model's
 * status values.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum CertCoverage {

    NONE("none", BadgeVariant.OUTLINE, Microcopy.of("none").withFilter("scope", "site_domains")),
    /** HTTPS is not this proxy's to give: a TLS passthrough site terminates nothing here. */
    NOT_USED("not_used", BadgeVariant.OUTLINE, Microcopy.of("not_used").withFilter("scope", "site_domains")),
    /**
     * A pattern answers many names, so no one certificate answers for it: HTTPS works for each name a certificate
     * covers. The fourth state without a certificate, so a catch-all's HTTPS cell says so instead of staying empty.
     */
    PATTERN("pattern", BadgeVariant.OUTLINE, Microcopy.of("per_name").withFilter("scope", "site_domains")),
    ACTIVE(CertificateModel.STATUS_ACTIVE, BadgeVariant.SUCCESS,
        Microcopy.of("covered").withFilter("scope", "site_domains")),
    PENDING(CertificateModel.STATUS_PENDING, BadgeVariant.WARNING,
        Microcopy.of("coverage_pending").withFilter("scope", "site_domains")),
    ERROR(CertificateModel.STATUS_ERROR, BadgeVariant.DESTRUCTIVE,
        Microcopy.of("not_covered").withFilter("scope", "site_domains"));

    private final String key;
    private final BadgeVariant badgeVariant;
    private final Microcopy label;

    CertCoverage(@NonNull String key, @NonNull BadgeVariant badgeVariant, @NonNull Microcopy label) {
        this.key = key;
        this.badgeVariant = badgeVariant;
        this.label = label;
    }

    /** @return the rendered {@code data-cert-status} value */
    public @NonNull String key() {
        return this.key;
    }

    /** @return the pl-badge variant this state wears */
    public @NonNull BadgeVariant badgeVariant() {
        return this.badgeVariant;
    }

    /** @return the state's wording for a reader who may not open the certificate itself */
    public @NonNull Microcopy label() {
        return this.label;
    }

    /** @return whether a certificate record stands behind this state */
    public boolean hasCertificate() {
        return switch (this) {
            case NONE, NOT_USED, PATTERN -> false;
            case ACTIVE, PENDING, ERROR -> true;
        };
    }

    /**
     * The coverage a covering certificate's stored status means.
     *
     * @param status the certificate's STATUS value, null when no certificate covers the host
     */
    public static @NonNull CertCoverage ofCertificateStatus(@Nullable String status) {
        if (status == null) {
            return NONE;
        }
        for (CertCoverage coverage : values()) {
            if (coverage.hasCertificate() && coverage.key.equals(status)) {
                return coverage;
            }
        }
        return ERROR;
    }
}
