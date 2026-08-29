package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * A deleted certificate releases every domain row that pinned it: {@code certificate_id}
 * is cleared, so the row falls back to platform selection (the SAN walk in
 * {@link CertificateStore}) instead of claiming a certificate nothing can load.
 *
 * AIDEV-NOTE: CLEAR, never refuse, because that is what the delete dialog promises
 * ("these names stop serving HTTPS until another certificate covers them") and what the
 * TLS tier already does at handshake time -- a dangling pin was skipped by
 * {@code buildPreferredAliases} and the SNI lookup fell through to the SAN map. The row
 * kept CLAIMING the pin, though: the form showed a certificate that did not exist and
 * {@code CertificateStore} loaded the row for nothing. Nothing else reads the column
 * (renewal picks by hostname, not by pin), so there is no consumer for a refusal to protect.
 *
 * AIDEV-NOTE: the release is a set-based {@code updateAll} correlated over the pending
 * delete's own criteria, so it fires on every delete lane (the CMS, a criteria delete, the
 * orphan sweep) in one statement and never re-reads the doomed certificates. It bypasses
 * the domain row's write hooks on purpose: the route invariant, the tenant rule and the
 * claim release all judge columns this write does not touch.
 */
public final class CertificateCascades {

    private static volatile boolean installed;

    private CertificateCascades() {
    }

    /** Install the release hook; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        CertificateModel.SCHEMA.addBeforeRemoveHook(context ->
            Models.get(SiteDomainModel.class).find()
                .where(PendingDeletes.dependents(SiteDomainModel.CERTIFICATE, context))
                .assign(SiteDomainModel.CERTIFICATE_ID, null)
                .updateAll());
    }
}
