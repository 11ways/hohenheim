package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * A certificate's last failed issuance or renewal: the stored reason and when it failed, in the words the admin shows.
 *
 * AIDEV-NOTE: the moment is the certificate's own {@link CertificateModel#RENEWAL_FAILED_AT}, which
 * {@link AcmeService#recordRenewalFailure} stamps beside the stored reason; never its activity, which may be pruned. A
 * failure stored before M017 whose activity entry was already gone has no moment.
 *
 * @param reason the stored failure text, the raw problem kept for the log and the technical detail
 * @param at     when it failed, null when no entry says
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record RenewalFailure(@NonNull String reason, @Nullable Instant at) {

    public RenewalFailure {
        Objects.requireNonNull(reason, "reason cannot be null");
    }

    /** @return whether the certificate's last issuance or renewal failed and says why */
    public static boolean failing(@NonNull Row cert) {
        Integer errors = cert.get(CertificateModel.ERROR_COUNT);
        String reason = cert.get(CertificateModel.RENEWAL_ERROR);
        boolean errored = CertificateModel.STATUS_ERROR.equals(cert.get(CertificateModel.STATUS))
            || (errors != null && errors > 0);
        return errored && reason != null && !reason.isBlank();
    }

    /** @return the certificate's last failure, null when its last issuance or renewal did not fail */
    public static @Nullable RenewalFailure of(@NonNull Row cert) {
        if (!failing(cert)) {
            return null;
        }
        String reason = cert.get(CertificateModel.RENEWAL_ERROR);
        return new RenewalFailure(reason.trim(), cert.get(CertificateModel.RENEWAL_FAILED_AT));
    }

    /** @return the failure in plain words, never its raw text */
    public @NonNull Microcopy sentence() {
        return sentenceFor(this.reason);
    }

    /**
     * A stored or thrown failure text in plain words: an authority refusal names the refused hostname, an ACME problem
     * its cause ({@link AcmeProblem}).
     */
    public static @NonNull Microcopy sentenceFor(@Nullable String failure) {
        CertificateAuthority.Refused refused = CertificateAuthority.Refused.storedIn(failure);
        return refused != null ? refused.sentence() : AcmeProblem.sentenceFor(failure);
    }
}
