package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Addresses list reads each address the way the Domains board does: the app it serves, whether the name points
 * at this proxy, and what HTTPS gives it, from the same per-name rule the app overview and the app's verdict read.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class DomainsListJourneyTest extends HohenheimTestBase {

    @Test
    void anAddressSaysWhereItPointsAndWhatHttpsGivesIt() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String here = "here-" + suffix + ".test";
        String elsewhere = "away-" + suffix + ".test";
        String covered = "covered-" + suffix + ".test";
        var servers = Models.get(ServerModel.class);
        Row local = servers.findById(ServerModel.localServerId());
        String declared = local.get(ServerModel.PUBLIC_IPV4);
        try (OutboundFixture pointsHere = OutboundFixture.route(here, 9);
             OutboundFixture pointsAway = OutboundFixture.route(elsewhere, 9)) {
            local.set(ServerModel.PUBLIC_IPV4, pointsHere.publicAddress().getHostAddress());
            servers.save(local);

            Row site = site("addresses-" + suffix);
            Row hereRow = domain(site, here, false);
            Row awayRow = domain(site, elsewhere, true);
            Row coveredRow = domain(site, covered, true);
            Row pattern = domain(site, "*.pattern-" + suffix + ".test", false);
            certificate("Covered " + suffix, covered);

            // 1. Points here: a name resolving to this host's declared address says yes; one resolving elsewhere says
            //    no and names where it points; a pattern has no single name and no answer.
            StateLineCell yes = DomainParts.reachCell(hereRow);
            assertThat(yes).as("step 1: an exact name gets an answer").isNotNull();
            assertThat(yes.state()).as("step 1: the name points here").isEqualTo("points_here");
            StateLineCell no = DomainParts.reachCell(awayRow);
            assertThat(no.state()).as("step 1: the other name points elsewhere").isEqualTo("points_elsewhere");
            assertThat(String.valueOf(no.detail().args().get("addresses")))
                .as("step 1: naming the address it points to")
                .isEqualTo(pointsAway.publicAddress().getHostAddress());
            assertThat(DomainParts.reachCell(pattern)).as("step 1: a pattern has no answer").isNull();

            // 2. HTTPS: a name forced to HTTPS without a working certificate is the error page visitors get, a covered
            //    name works, an unforced name without one has none, and a pattern has no verdict.
            var working = CertificateCoverage.activeNames();
            assertThat(AppHealth.httpsOf(awayRow, false, working)).as("step 2: forced without a certificate")
                .isEqualTo(CertCoverage.ERROR);
            assertThat(AppHealth.httpsOf(coveredRow, false, working)).as("step 2: covered by a working one")
                .isEqualTo(CertCoverage.ACTIVE);
            assertThat(AppHealth.httpsOf(hereRow, false, working)).as("step 2: unforced, no certificate")
                .isEqualTo(CertCoverage.NONE);
            assertThat(AppHealth.httpsOf(pattern, false, working)).as("step 2: a pattern has no verdict").isNull();
            assertThat(AppHealth.httpsOf(hereRow, true, working)).as("step 2: passthrough terminates nothing here")
                .isEqualTo(CertCoverage.NOT_USED);

            // 3. The list renders both answers per row, and offers "Get a certificate" for the uncovered name (that a
            //    covered name is never offered one is AdminPagesTest's, through the action's own visibility).
            HttpResponse<String> page = adminGet("/admin/domains?q=" + suffix);
            assertThat(page.statusCode()).as("step 3: the Addresses list renders").isEqualTo(200);
            String html = page.body();
            assertThat(html).as("step 3: a points-here cell for the name that points here")
                .contains("data-state=\"points_here\"");
            assertThat(html).as("step 3: and for the one pointing elsewhere")
                .contains("data-state=\"points_elsewhere\"");
            assertThat(html).as("step 3: the forced name without a certificate reads broken")
                .contains("data-cert-status=\"" + CertCoverage.ERROR.key() + "\"");
            assertThat(html).as("step 3: the covered name reads as working")
                .contains("data-cert-status=\"" + CertCoverage.ACTIVE.key() + "\"");
            assertThat(html).as("step 3: Get a certificate is offered for the uncovered name")
                .contains("request_domain_certificate");

            // 4. The address's own page heads with the same answers.
            HttpResponse<String> detail = adminGet("/admin/domains/" + hereRow.get(SiteDomainModel.ID));
            assertThat(detail.body()).as("step 4: the lead line names the app and says it points here")
                .contains("addresses-" + suffix).contains("points here: Yes");
        } finally {
            local = servers.findById(ServerModel.localServerId());
            local.set(ServerModel.PUBLIC_IPV4, declared);
            servers.save(local);
        }
    }

    private static Row site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }

    private static Row domain(Row site, String hostname, boolean forced) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.FORCE_SSL, forced);
        domains.save(domain);
        return domains.findById(domain.get(SiteDomainModel.ID));
    }

    private static void certificate(String name, String hostname) {
        var certs = Models.get(CertificateModel.class);
        Row cert = certs.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, name);
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, hostname);
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_HTTP);
        cert.set(CertificateModel.AUTO_RENEW, true);
        certs.save(cert);
    }
}
