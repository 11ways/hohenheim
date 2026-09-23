package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Whether a certificate covers one exact hostname, and in what state: the vocabulary a domain row renders.
 *
 * AIDEV-NOTE: the three covered states DERIVE their key from CertificateModel's own STATUS values
 * (the declaring home); NONE is the one state that model cannot express. How a state renders -- the
 * {@code data-cert-status} key, the badge variant and the wording a reader who may not open the
 * certificate sees -- is a fact on the member, so the domains tab compares no literal. An unknown
 * certificate status fails CLOSED onto {@link #ERROR}: a coverage badge never claims coverage it
 * cannot vouch for. CertCoverageVocabularyTest binds the members to the model's status values.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum CertCoverage {

    NONE("none", "outline", Microcopy.of("none").withFilter("scope", "site_domains")),
    ACTIVE(CertificateModel.STATUS_ACTIVE, "success",
        Microcopy.of("covered").withFilter("scope", "site_domains")),
    PENDING(CertificateModel.STATUS_PENDING, "warning",
        Microcopy.of("coverage_pending").withFilter("scope", "site_domains")),
    ERROR(CertificateModel.STATUS_ERROR, "destructive",
        Microcopy.of("not_covered").withFilter("scope", "site_domains"));

    private final String key;
    private final String badgeVariant;
    private final Microcopy label;

    CertCoverage(@NonNull String key, @NonNull String badgeVariant, @NonNull Microcopy label) {
        this.key = key;
        this.badgeVariant = badgeVariant;
        this.label = label;
    }

    /** @return the rendered {@code data-cert-status} value */
    public @NonNull String key() {
        return this.key;
    }

    /** @return the pl-badge variant this state wears */
    public @NonNull String badgeVariant() {
        return this.badgeVariant;
    }

    /** @return the state's wording for a reader who may not open the certificate itself */
    public @NonNull Microcopy label() {
        return this.label;
    }

    /** @return whether a certificate record stands behind this state */
    public boolean hasCertificate() {
        return this != NONE;
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
