package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-email ACME account keying: normalization, key-pair persistence per account,
 * and the cert row carrying its account email. No live ACME round-trips.
 */
class AcmeAccountEmailTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        UpstreamKindHandlers.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void normalizationMapsDefaultsToTheGlobalAccount() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, "ops@example.com");

        assertThat(AcmeService.normalizeAccountEmail(null)).isEmpty();
        assertThat(AcmeService.normalizeAccountEmail("   ")).isEmpty();
        // The global setting's own email maps to the global account, case-insensitively.
        assertThat(AcmeService.normalizeAccountEmail(" OPS@Example.COM ")).isEmpty();

        assertThat(AcmeService.normalizeAccountEmail("Tenant@Other.example "))
            .isEqualTo("tenant@other.example");
    }

    @Test
    void accountKeyPairsAreKeyedAndPersistedPerEmail() throws Exception {
        AcmeService service = new AcmeService(new CertificateStore());

        KeyPair global = service.loadOrCreateAccountKeyPair("");
        KeyPair globalAgain = service.loadOrCreateAccountKeyPair("");
        KeyPair tenant = service.loadOrCreateAccountKeyPair("certs@tenant.example");
        KeyPair tenantAgain = service.loadOrCreateAccountKeyPair("certs@tenant.example");

        // Stable per key, distinct across keys.
        assertThat(globalAgain.getPublic().getEncoded()).isEqualTo(global.getPublic().getEncoded());
        assertThat(tenantAgain.getPublic().getEncoded()).isEqualTo(tenant.getPublic().getEncoded());
        assertThat(tenant.getPublic().getEncoded()).isNotEqualTo(global.getPublic().getEncoded());

        // One acme_account row per account; the global one has no email.
        var certModel = Models.get(CertificateModel.class);
        List<Row> accountRows = certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_ACME_ACCOUNT))
            .all();
        assertThat(accountRows).hasSize(2);

        long withEmail = accountRows.stream()
            .filter(r -> "certs@tenant.example".equals(r.get(CertificateModel.LETSENCRYPT_EMAIL)))
            .count();
        long global_ = accountRows.stream()
            .filter(r -> r.get(CertificateModel.LETSENCRYPT_EMAIL) == null)
            .count();
        assertThat(withEmail).isEqualTo(1);
        assertThat(global_).isEqualTo(1);
    }

    @Test
    void requestCertificateStoresTheEmailOnTheCertRow() {
        AcmeService service = new AcmeService(new CertificateStore());

        // The name is SERVED (so CertificateAuthority lets the order start) but is not a
        // legal hostname, so it fails during validation before any CA contact -- and the
        // row, with its account email, must already be persisted by then.
        serveHostname("not a hostname");
        int certId = service.requestCertificate(
            List.of("not a hostname"), "Email Test", "certs@tenant.example",
            CertificateAuthority.Requester.SYSTEM);
        assertThat(certId).isEqualTo(-1);

        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.find()
            .where(CertificateModel.NICE_NAME.eq("Email Test"))
            .first();

        assertThat(cert).isNotNull();
        assertThat((String) cert.get(CertificateModel.LETSENCRYPT_EMAIL))
            .isEqualTo("certs@tenant.example");
        assertThat((String) cert.get(CertificateModel.STATUS))
            .isEqualTo(CertificateModel.STATUS_ERROR);
    }

    /** A site plus one exact domain row, which is what makes a hostname requestable at all. */
    private static void serveHostname(String hostname) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Acme Email Site " + hostname);
        site.set(SiteModel.SLUG, "acme-email-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "acme-email-seed.example.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(domain);
        // AIDEV-NOTE: the illegal spelling is written with a SET-BASED update, which runs no
        // schema hook, because SiteDomainModel.validateHostnameSyntax now refuses it on
        // every save path. That is the point of this fixture: it is a row predating the
        // constraint (M079's declared residue), and it is what keeps AcmeService's own
        // syntax check -- the second gate -- honestly covered.
        domainModel.find().where(SiteDomainModel.ID.eq(domain.get(SiteDomainModel.ID)))
            .assign(SiteDomainModel.HOSTNAME, hostname)
            .updateAll();
    }
}
