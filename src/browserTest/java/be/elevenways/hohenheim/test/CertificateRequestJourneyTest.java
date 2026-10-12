package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.CertificateOperations;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.tls.AcmeProblem;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Getting a Let's Encrypt certificate from the admin: offered dead while Let's Encrypt is off, refused before any
 * order when a name visibly points elsewhere, the typed input kept, and failures told as sentences.
 *
 * AIDEV-NOTE: nothing here reaches Let's Encrypt: every step is refused before AcmeService places an order.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class CertificateRequestJourneyTest extends HohenheimTestBase {

    private static ProxyServer adoptedProxy;

    @BeforeAll
    static void adoptProxy() {
        if (ServerMain.getProxyServer() == null) {
            adoptedProxy = new ProxyServer();
            ServerMain.adoptProxyServer(adoptedProxy);
        }
    }

    @AfterAll
    static void detachProxy() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
        if (adoptedProxy != null) {
            ServerMain.adoptProxyServer(null);
        }
    }

    @Test
    void aCertificateIsRequestedOnlyWhenItCanWork() throws Exception {
        var servers = Models.get(ServerModel.class);
        Row local = servers.findById(ServerModel.localServerId());
        String declared = local.get(ServerModel.PUBLIC_IPV4);
        try {
            // 1. Let's Encrypt is switched off: the certificate list offers the request dead, with the reason, and a
            //    submit is refused before anything is written.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
            // The shipped sentence is "Let's Encrypt is switched off in Settings."; its apostrophe is escaped on the
            // page.
            String disabled = "is switched off in Settings";
            assertThat(adminGet("/admin/" + HohenheimSlugs.CERTIFICATES).body())
                .as("step 1: the request is offered with the reason it cannot run").contains(disabled);
            HttpResponse<String> off = request("off.request-journey.test");
            assertThat(off.statusCode()).as("step 1: a submit while off is refused").isGreaterThanOrEqualTo(400);
            assertThat(certificateNamed("off.request-journey.test")).as("step 1: and no order was written").isNull();

            // 2. Switched on, a name that resolves to an address this host does not declare is refused before an
            //    order, with where it points, and the typed name is kept in the redrawn input.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
            local.set(ServerModel.PUBLIC_IPV4, "203.0.113.10");
            servers.save(local);
            try (OutboundFixture elsewhereDns = OutboundFixture.route("elsewhere.request-journey.test", 9)) {
                String pointsTo = elsewhereDns.publicAddress().getHostAddress();
                HttpResponse<String> elsewhere = request("elsewhere.request-journey.test");
                assertThat(elsewhere.statusCode()).as("step 2: refused as input").isEqualTo(422);
                assertThat(elsewhere.body()).as("step 2: naming where the name points")
                    .contains("elsewhere.request-journey.test does not point here yet, it points to " + pointsTo);
                assertThat(elsewhere.body()).as("step 2: the typed name is redrawn")
                    .contains("elsewhere.request-journey.test");
            }
            assertThat(certificateNamed("elsewhere.request-journey.test")).as("step 2: and no order was written")
                .isNull();

            // 3. A certificate with no manual DNS-01 order waiting offers no "Verify DNS", and says so.
            Row issued = Models.get(CertificateModel.class).createEmptyRow();
            issued.set(CertificateModel.NICE_NAME, "request-journey-issued");
            issued.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
            issued.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            issued.set(CertificateModel.DOMAIN_NAMES_TEXT, "issued.request-journey.test");
            Models.get(CertificateModel.class).save(issued);
            String detail = adminGet("/admin/" + HohenheimSlugs.CERTIFICATES + "/" + issued.get(CertificateModel.ID))
                .body();
            assertThat(detail).as("step 3: the overview says no DNS records wait")
                .contains("No DNS records to publish")
                .doesNotContain("data-action-id=\"" + CertificateOperations.CONTINUE_DNS.id() + "\"");
            String verify = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES,
                    CertificateOperations.CONTINUE_DNS.id())
                .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf((Object) issued.get(CertificateModel.ID))).toUrl();
            assertThat(adminPostForm(verify, ApiSupport.invokeTransport()).statusCode())
                .as("step 3: and refuses a verify with no order behind it").isGreaterThanOrEqualTo(400);

            // 4. What Let's Encrypt answers is told as the sentence for its problem type, never the raw problem.
            assertThat(AcmeProblem.sentenceFor("Order rejected by CA: type=urn:ietf:params:acme:error:rateLimited,"
                    + " detail=too many certificates").key())
                .as("step 4: a known problem type is its own sentence").isEqualTo("acme_rate_limited");
            assertThat(AcmeProblem.sentenceFor("connection reset").key())
                .as("step 4: anything else points at where the detail is").isEqualTo("acme_other");
        } finally {
            local = servers.findById(ServerModel.localServerId());
            local.set(ServerModel.PUBLIC_IPV4, declared);
            servers.save(local);
        }
    }

    /** A finished order opens the certificate it wrote at its overview, never at the edit form with its PEM fields. */
    @Test
    void aFinishedOrderOpensTheCertificatesOverview() throws Exception {
        String savedDirectory = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        String savedEmail = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        String host = "landing.request-journey.test";
        try (FakeAcmeServer ca = new FakeAcmeServer()) {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, ca.directoryUrl());
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, "acme-test@example.com");

            // 1. An address's row orders its certificate ("Get a certificate") with a manual DNS-01 challenge, so
            //    nothing has to reach this host: the order waits for its TXT records.
            Row domain = address(host);
            String invoke = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.DOMAINS,
                    CertificateOperations.REQUEST_FOR_DOMAIN.id())
                .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf((Object) domain.get(SiteDomainModel.ID))).toUrl();
            HttpResponse<String> ordered = adminPostForm(invoke, ApiSupport.form("domains", host,
                "nice_name", "request-journey-landing", "challenge_type", CertificateModel.CHALLENGE_DNS,
                "dns_publisher", CertificateModel.DNS_PUBLISHER_MANUAL) + "&" + ApiSupport.invokeTransport());
            Row certificate = certificateNamed(host);
            assertThat(certificate).as("step 1: the order wrote its certificate (answered %s)", ordered.statusCode())
                .isNotNull();
            Object id = certificate.get(CertificateModel.ID);

            // 2. THE ASSERTION: the answer opens that certificate at its front door, never at its record page, which
            //    is the edit form for an operator.
            assertThat(landingOf(ordered)).as("step 2: the order opens the certificate's front door")
                .isEqualTo(CmsRoutes.open(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES, id).toUrl());

            // 3. That door is the overview, where the waiting order's TXT records are.
            HttpResponse<String> front = adminGet(landingOf(ordered));
            assertThat(landingOf(front)).as("step 3: the front door is the overview")
                .startsWith(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES, id,
                    RecordOverview.SLUG).toUrl());
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, savedDirectory);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, savedEmail);
        }
    }

    private HttpResponse<String> request(String hostname) throws Exception {
        return adminPostForm(ApiSupport.requestCertificateTarget(), ApiSupport.form(
            "domains", hostname, "nice_name", "request-journey", "challenge_type", "http") + "&"
            + ApiSupport.invokeTransport());
    }

    /** @return a stored address row of a fresh redirect site */
    private static Row address(String hostname) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "request-journey " + hostname);
        site.set(SiteModel.SLUG, "request-journey-landing");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:redirect");
        site.set(SiteModel.SETTINGS, Map.of("target", "https://example.com"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(domain);
        return domain;
    }

    private static Row certificateNamed(String hostname) {
        return Models.get(CertificateModel.class).find()
            .where(CertificateModel.DOMAIN_NAMES_TEXT.eq(hostname)).first();
    }
}
