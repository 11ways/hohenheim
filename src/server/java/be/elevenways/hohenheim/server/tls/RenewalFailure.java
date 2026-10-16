package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * A certificate's last failed issuance or renewal: the stored reason and when it failed, in the words the admin shows.
 *
 * AIDEV-NOTE: the moment is the newest {@link HohenheimActivityAction#RENEWAL_FAILED} entry of the certificate, which
 * {@link AcmeService#recordRenewalFailure} writes beside the stored reason; a certificate has no column for it, and the
 * entry is also the failure's line in its history. A failure stored before that entry existed, or one whose entry
 * retention removed, has no moment.
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
        Object id = cert.get(CertificateModel.ID);
        Row entry = id == null ? null : Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(CertificateModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(id)))
            .where(ActivityModel.ACTION.eq(HohenheimActivityAction.RENEWAL_FAILED.id().toString()))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        return new RenewalFailure(reason.trim(), entry == null ? null : entry.get(ActivityModel.CREATED_AT));
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
