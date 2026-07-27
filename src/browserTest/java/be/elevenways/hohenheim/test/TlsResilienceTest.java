package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-level tests for TLS resilience: hostname validation, error backoff, the widened
 * renewal sweep, and orphan-certificate cleanup. No Playwright, no live ACME.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TlsResilienceTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    private static Row createCert(String niceName, String provider, String status, String domains) {
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, niceName);
        cert.set(CertificateModel.PROVIDER, provider);
        cert.set(CertificateModel.STATUS, status);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, domains);
        certModel.save(cert);
        return cert;
    }

    @Test
    @Order(1)
    void hostnameValidatorRejectsBadNames() {
        assertThat(AcmeService.isValidHostname("example.com")).isTrue();
        assertThat(AcmeService.isValidHostname("sub.example-site.co.uk")).isTrue();

        assertThat(AcmeService.isValidHostname(null)).isFalse();
        assertThat(AcmeService.isValidHostname("")).isFalse();
        assertThat(AcmeService.isValidHostname("nodots")).isFalse();
        assertThat(AcmeService.isValidHostname("*.example.com")).isFalse();   // HTTP-01 can't do wildcards
        assertThat(AcmeService.isValidHostname("-bad.example.com")).isFalse();
        assertThat(AcmeService.isValidHostname("bad-.example.com")).isFalse();
        assertThat(AcmeService.isValidHostname("exa mple.com")).isFalse();
        assertThat(AcmeService.isValidHostname("http://example.com")).isFalse();
        assertThat(AcmeService.isValidHostname("a".repeat(64) + ".com")).isFalse();

        assertThat(AcmeService.invalidHostnames(List.of("good.example.com", "*.bad.com")))
            .containsExactly("*.bad.com");
        assertThat(AcmeService.invalidHostnames(
            List.of("good.example.com", "*.example.com"), true)).isEmpty();
        assertThat(AcmeService.invalidHostnames(List.of("*.*.example.com"), true))
            .containsExactly("*.*.example.com");
    }

    @Test
    @Order(2)
    void failureBackoffEscalatesAndSuccessResets() {
        Row cert = createCert("Backoff", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "backoff.test");

        Instant before = Instant.now();
        AcmeService.recordRenewalFailure(cert, "boom");
        assertThat((String) cert.get(CertificateModel.STATUS)).isEqualTo(CertificateModel.STATUS_ERROR);
        assertThat((Integer) cert.get(CertificateModel.ERROR_COUNT)).isEqualTo(1);
        Instant first = cert.get(CertificateModel.NEXT_ATTEMPT_AT);
        assertThat(first).isAfter(before);

        AcmeService.recordRenewalFailure(cert, "boom again");
        assertThat((Integer) cert.get(CertificateModel.ERROR_COUNT)).isEqualTo(2);

        AcmeService.markRenewalSuccess(cert);
        assertThat((String) cert.get(CertificateModel.STATUS)).isEqualTo(CertificateModel.STATUS_ACTIVE);
        assertThat((Integer) cert.get(CertificateModel.ERROR_COUNT)).isEqualTo(0);
        assertThat((Instant) cert.get(CertificateModel.NEXT_ATTEMPT_AT)).isNull();
        assertThat((String) cert.get(CertificateModel.RENEWAL_ERROR)).isNull();
    }

    @Test
    @Order(3)
    void backoffDelayEscalatesWithJitterBounds() {
        Instant now = Instant.now();
        // 15min * 2^min(count,7), +/-20% jitter
        for (int count : new int[]{1, 3, 7, 12}) {
            long baseSeconds = 15L * 60L * (1L << Math.min(count, 7));
            Instant next = AcmeService.computeNextAttempt(count, now);
            long delta = next.getEpochSecond() - now.getEpochSecond();
            assertThat(delta)
                .as("backoff for error_count=" + count)
                .isBetween((long) (baseSeconds * 0.8) - 1, (long) (baseSeconds * 1.2) + 1);
        }
    }

    @Test
    @Order(4)
    void renewalSweepIncludesDueErroredCertsOnly() {
        var certModel = Models.get(CertificateModel.class);
        Instant now = Instant.now();

        Row dueError = createCert("Due Error", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ERROR, "due-error.test");
        dueError.set(CertificateModel.NEXT_ATTEMPT_AT, now.minus(1, ChronoUnit.HOURS));
        certModel.save(dueError);

        Row nullError = createCert("Null Error", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ERROR, "null-error.test");

        Row futureError = createCert("Future Error", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ERROR, "future-error.test");
        futureError.set(CertificateModel.NEXT_ATTEMPT_AT, now.plus(1, ChronoUnit.HOURS));
        certModel.save(futureError);

        Row expiringActive = createCert("Expiring Active", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "expiring.test");
        expiringActive.set(CertificateModel.EXPIRES_ON, now.plus(5, ChronoUnit.DAYS));
        certModel.save(expiringActive);

        Row freshActive = createCert("Fresh Active", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "fresh.test");
        freshActive.set(CertificateModel.EXPIRES_ON, now.plus(80, ChronoUnit.DAYS));
        certModel.save(freshActive);

        Row customError = createCert("Custom Error", CertificateModel.PROVIDER_CUSTOM,
            CertificateModel.STATUS_ERROR, "custom-error.test");

        List<String> candidates = AcmeService.findRenewalCandidates(certModel, now).stream()
            .map(r -> (String) r.get(CertificateModel.NICE_NAME))
            .toList();

        assertThat(candidates)
            .contains("Due Error", "Null Error", "Expiring Active")
            .doesNotContain("Future Error", "Fresh Active", "Custom Error");
    }

    @Test
    @Order(5)
    void orphanCleanupDeletesUnlinkedLetsencryptOnly() {
        var certModel = Models.get(CertificateModel.class);
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        // An enabled site linked to linked.test
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Orphan Test Site");
        site.set(SiteModel.SLUG, "orphan-test-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        siteModel.save(site);

        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "linked.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(domain);

        // Partially linked: one SAN still in use -> keep
        Row partiallyLinked = createCert("Partially Linked", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "gone.test,linked.test");

        // Fully unlinked letsencrypt cert -> delete
        Row orphan = createCert("Orphan", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "gone-a.test,gone-b.test");

        // Unlinked custom cert -> never touched
        Row custom = createCert("Custom Keep", CertificateModel.PROVIDER_CUSTOM,
            CertificateModel.STATUS_ACTIVE, "gone-custom.test");

        CleanOrphanCertificates.clean();

        assertThat(certModel.findById((Integer) partiallyLinked.get(CertificateModel.ID))).isNotNull();
        assertThat(certModel.findById((Integer) orphan.get(CertificateModel.ID))).isNull();
        assertThat(certModel.findById((Integer) custom.get(CertificateModel.ID))).isNotNull();
    }

    @Test
    @Order(6)
    void disabledSitesKeepTheirCertsButDeletedSitesOrphanThem() {
        var certModel = Models.get(CertificateModel.class);
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        // A temporarily DISABLED site: its cert must survive the sweep, or a
        // toggle-off/toggle-on cycle forces LE re-issuance (rate-limit pain).
        Row disabledSite = siteModel.createEmptyRow();
        disabledSite.set(SiteModel.NAME, "Disabled Keep Site");
        disabledSite.set(SiteModel.SLUG, "disabled-keep-site");
        disabledSite.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        disabledSite.set(SiteModel.ENABLED, false);
        disabledSite.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        siteModel.save(disabledSite);

        Row disabledDomain = domainModel.createEmptyRow();
        disabledDomain.set(SiteDomainModel.SITE_ID, disabledSite.get(SiteModel.ID));
        disabledDomain.set(SiteDomainModel.HOSTNAME, "disabled-keep.test");
        disabledDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(disabledDomain);

        Row keptCert = createCert("Disabled Keep", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "disabled-keep.test");

        // A soft-DELETED site: its cert is genuinely orphaned and gets removed
        // (which also stops its pointless auto-renewals).
        Row deletedSite = siteModel.createEmptyRow();
        deletedSite.set(SiteModel.NAME, "Deleted Orphan Site");
        deletedSite.set(SiteModel.SLUG, "deleted-orphan-site");
        deletedSite.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        deletedSite.set(SiteModel.ENABLED, true);
        deletedSite.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        deletedSite.set(SiteModel.DELETED_AT, java.time.Instant.now());
        siteModel.save(deletedSite);

        Row deletedDomain = domainModel.createEmptyRow();
        deletedDomain.set(SiteDomainModel.SITE_ID, deletedSite.get(SiteModel.ID));
        deletedDomain.set(SiteDomainModel.HOSTNAME, "deleted-orphan.test");
        deletedDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(deletedDomain);

        Row orphanedCert = createCert("Deleted Orphan", CertificateModel.PROVIDER_LETSENCRYPT,
            CertificateModel.STATUS_ACTIVE, "deleted-orphan.test");

        CleanOrphanCertificates.clean();

        assertThat(certModel.findById((Integer) keptCert.get(CertificateModel.ID)))
            .as("a disabled site's cert must survive the orphan sweep")
            .isNotNull();
        assertThat(certModel.findById((Integer) orphanedCert.get(CertificateModel.ID)))
            .as("a soft-deleted site's cert is orphaned")
            .isNull();
    }

    @Test
    @Order(60)
    @Timeout(30)
    void verboseDnsHookIsDrainedInsteadOfMisreportedAsTimeout() throws Exception {
        File hook = File.createTempFile("hh-dns-hook", ".sh");
        hook.deleteOnExit();
        // Emits far more than the OS pipe buffer: an undrained stdout would
        // block the hook forever and surface as a bogus 60s timeout.
        java.nio.file.Files.writeString(hook.toPath(),
            "#!/bin/sh\nhead -c 262144 /dev/zero | tr '\\0' 'x'\nexit 0\n");
        hook.setExecutable(true);
        String previous = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND, hook.getAbsolutePath());
        try {
            new CommandDnsTxtPublisher().publish(
                new DnsTxtRecord("_acme-challenge.example.com", "verbose-hook-value"));
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND, previous);
        }
    }

    @Test
    @Order(61)
    @Timeout(30)
    void failingDnsHookSurfacesItsOutputInTheError() throws Exception {
        File hook = File.createTempFile("hh-dns-hook-fail", ".sh");
        hook.deleteOnExit();
        java.nio.file.Files.writeString(hook.toPath(),
            "#!/bin/sh\necho 'zone not found'\nexit 3\n");
        hook.setExecutable(true);
        String previous = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND, hook.getAbsolutePath());
        try {
            new CommandDnsTxtPublisher().publish(
                new DnsTxtRecord("_acme-challenge.example.com", "failing-hook-value"));
            org.junit.jupiter.api.Assertions.fail("a non-zero hook exit must throw");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("exit 3").contains("zone not found");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_HOOK_COMMAND, previous);
        }
    }
}
