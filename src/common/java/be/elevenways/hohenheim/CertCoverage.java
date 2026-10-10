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
 * AIDEV-NOTE: the three covered states DERIVE their token from CertificateModel's own STATUS values
 * (the declaring home); NONE is the one state that model cannot express. How a state renders -- the
 * {@code data-state} token, the badge variant and the wording a reader who may not open the
 * certificate sees -- is a fact on the member, so the domains tab compares no literal. An unknown
 * certificate status fails CLOSED onto {@link #ERROR}: a coverage badge never claims coverage it
 * cannot vouch for. NOT_USED is the second state without a certificate, for a name this proxy terminates no TLS for;
 * PATTERN the third, for an address that is a pattern. DashboardVocabularyDriftTest binds the members to the model's
 * status values.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum CertCoverage implements WordedState {

    NONE("none", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("none")),
    /** HTTPS is not this proxy's to give: a TLS passthrough site terminates nothing here. */
    NOT_USED("not_used", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("not_used")),
    /**
     * A pattern answers many names, so no one certificate answers for it: HTTPS works for each name a certificate
     * covers. The fourth state without a certificate, so a catch-all's HTTPS cell says so instead of staying empty.
     */
    PATTERN("pattern", BadgeVariant.OUTLINE, HohenheimMicrocopy.SITE_DOMAINS.of("per_name")),
    ACTIVE(CertificateModel.STATUS_ACTIVE, BadgeVariant.SUCCESS,
        HohenheimMicrocopy.SITE_DOMAINS.of("covered")),
    PENDING(CertificateModel.STATUS_PENDING, BadgeVariant.WARNING,
        HohenheimMicrocopy.SITE_DOMAINS.of("coverage_pending")),
    ERROR(CertificateModel.STATUS_ERROR, BadgeVariant.DESTRUCTIVE,
        HohenheimMicrocopy.SITE_DOMAINS.of("not_covered"));

    private final String token;
    private final BadgeVariant variant;
    private final Microcopy label;

    CertCoverage(@NonNull String token, @NonNull BadgeVariant variant, @NonNull Microcopy label) {
        this.token = token;
        this.variant = variant;
        this.label = label;
    }

    /** @return the rendered {@code data-state} value */
    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return the state's wording for a reader who may not open the certificate itself */
    @Override
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
            if (coverage.hasCertificate() && coverage.token.equals(status)) {
                return coverage;
            }
        }
        return ERROR;
    }
}
