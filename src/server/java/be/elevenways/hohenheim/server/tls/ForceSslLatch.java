package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;

/**
 * Switches force_ssl on for an armed domain row ({@link SiteDomainModel#FORCE_SSL_AUTO}) the moment a working
 * certificate covers its hostname, and disarms the latch.
 *
 * AIDEV-NOTE: two moments, one rule. A certificate that becomes ACTIVE forces every armed row it covers (request,
 * reissue, manual DNS-01, renewal and manual upload all save the certificate row, so the hook on that row is the one
 * home); a domain row written while an active certificate already covers it forces itself in the same write. The latch
 * only ever switches force_ssl ON, once: a certificate lost later leaves force_ssl on, so the dispatcher keeps
 * failing closed, and an operator's explicit "off" disarmed the latch before it could fire (SiteDomainModel).
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public final class ForceSslLatch {

    private static volatile boolean installed;

    private ForceSslLatch() {
    }

    /** Install both hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        CertificateModel.SCHEMA.addAfterSaveHook(context -> {
            Row saved = context.getRow();
            Integer id = saved != null ? saved.get(CertificateModel.ID) : null;
            Row cert = id != null ? Models.get(CertificateModel.class).findById(id) : null;
            if (cert != null && CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))
                    && !CertificateModel.PROVIDER_ACME_ACCOUNT.equals(cert.get(CertificateModel.PROVIDER))) {
                fire(CertificateCoverage.namesOf(cert));
            }
        });
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && armed(row) && CertificateCoverage.covers(CertificateCoverage.activeNames(),
                    exactHostname(row))) {
                row.set(SiteDomainModel.FORCE_SSL, true);
                row.set(SiteDomainModel.FORCE_SSL_AUTO, false);
            }
        });
    }

    /** Forces every armed row the names cover, as declared system work, each write recorded in its activity. */
    private static void fire(@NonNull Collection<String> names) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        for (Row domain : domains.find().where(SiteDomainModel.FORCE_SSL_AUTO.eq(true)).all()) {
            String hostname = exactHostname(domain);
            if (!CertificateCoverage.covers(names, hostname)) {
                continue;
            }
            domain.set(SiteDomainModel.FORCE_SSL, true);
            domain.set(SiteDomainModel.FORCE_SSL_AUTO, false);
            ExecutionIdentity.runAsSystem("force-ssl-latch", () ->
                ActivityLog.withAction(HohenheimActivityAction.HTTPS_FORCED, hostname, () -> domains.save(domain)));
        }
    }

    private static boolean armed(@NonNull Row row) {
        Object armed = SiteDomainModel.effective(row, SiteDomainModel.FORCE_SSL_AUTO);
        // A create that does not carry the column gets the model's default: armed.
        return armed == null ? !row.has(SiteDomainModel.ID.getName()) : Boolean.TRUE.equals(armed);
    }

    /** @return the row's hostname when it routes as an exact name, else null (a pattern has no one certificate) */
    private static @Nullable String exactHostname(@NonNull Row row) {
        String hostname = (String) SiteDomainModel.effective(row, SiteDomainModel.HOSTNAME);
        String matchType = (String) SiteDomainModel.effective(row, SiteDomainModel.MATCH_TYPE);
        return SiteDomainModel.MATCH_EXACT.equals(SiteDomainModel.effectiveMatchType(hostname, matchType))
            ? hostname : null;
    }
}
