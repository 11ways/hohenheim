package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.ServerMain;
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
 * AIDEV-NOTE: two moments, one rule, and the rule is {@link CertificateCoverage#workingNames()}: what the running
 * proxy loaded, the stored ACTIVE rows only where no proxy runs in this process. A certificate that starts working
 * forces every armed row it covers: the proxy's store loading it ({@link #fire(CertificateStore)}, after every store
 * load the proxy makes) or, without a proxy, the row saved ACTIVE. A domain row written while a working certificate
 * already covers it forces itself in the same write. An ACTIVE row whose material the store cannot load forces
 * nothing (D10b: it used to, sending visitors to a handshake no certificate answers). The latch only ever switches
 * force_ssl ON, once: a certificate lost later leaves force_ssl on, so the dispatcher keeps failing closed, and an
 * operator's explicit "off" disarmed the latch before it could fire (SiteDomainModel).
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
            // With a proxy in this process the row is not the rule: its store loading the certificate fires.
            if (cert != null && ServerMain.getProxyServer() == null
                    && CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))
                    && !CertificateModel.PROVIDER_ACME_ACCOUNT.equals(cert.get(CertificateModel.PROVIDER))) {
                fire(CertificateCoverage.namesOf(cert));
            }
        });
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            // A create that does not carry the latch reads the model's default: armed.
            Row stored = StoredRows.of(Models.get(SiteDomainModel.class), row);
            if (Boolean.TRUE.equals(row.afterWrite(SiteDomainModel.FORCE_SSL_AUTO, stored))
                    && CertificateCoverage.covers(CertificateCoverage.workingNames(), exactHostname(row, stored))) {
                row.set(SiteDomainModel.FORCE_SSL, true);
                row.set(SiteDomainModel.FORCE_SSL_AUTO, false);
            }
        });
    }

    /**
     * Forces every armed row the store's loaded certificates cover: the proxy calls this after each load of its store.
     *
     * @return how many rows it forced, so the proxy rebuilds its routes when any changed
     */
    public static int fire(@NonNull CertificateStore store) {
        return fire(CertificateCoverage.workingNames(store));
    }

    /** Forces every armed row the names cover, as declared system work, each write recorded in its activity. */
    private static int fire(@NonNull Collection<String> names) {
        if (names.isEmpty()) {
            return 0;
        }
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        int forced = 0;
        for (Row domain : domains.find().where(SiteDomainModel.FORCE_SSL_AUTO.eq(true)).all()) {
            String hostname = exactHostname(domain, null);
            if (!CertificateCoverage.covers(names, hostname)) {
                continue;
            }
            domain.set(SiteDomainModel.FORCE_SSL, true);
            domain.set(SiteDomainModel.FORCE_SSL_AUTO, false);
            ExecutionIdentity.runAsSystem("force-ssl-latch", () ->
                ActivityLog.withAction(HohenheimActivityAction.HTTPS_FORCED, hostname, () -> domains.save(domain)));
            forced++;
        }
        return forced;
    }

    /**
     * @param stored the persisted row of a pending write, null on a create or when {@code row} is itself stored
     * @return the row's hostname when it routes as an exact name, else null (a pattern has no one certificate)
     */
    private static @Nullable String exactHostname(@NonNull Row row, @Nullable Row stored) {
        String hostname = row.afterWrite(SiteDomainModel.HOSTNAME, stored);
        String matchType = row.afterWrite(SiteDomainModel.MATCH_TYPE, stored);
        return SiteDomainModel.MATCH_EXACT.equals(SiteDomainModel.effectiveMatchType(hostname, matchType))
            ? hostname : null;
    }
}
