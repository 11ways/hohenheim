package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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
            Row excluded = domain(site, "elsewhere-cert-" + suffix + ".test", true);
            excluded.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, true);
            Models.get(SiteDomainModel.class).save(excluded);
            certificate("Covered " + suffix, covered + "," + "www." + covered);

            // 1. Points here: a name resolving to this host's declared address says yes; one resolving elsewhere says
            //    no and names where it points; a pattern has no single name and no answer.
            StateLineCell yes = DomainParts.reachCell(hereRow, HostnameReach.LOOKUP_WAIT_MS);
            assertThat(yes).as("step 1: an exact name gets an answer").isNotNull();
            assertThat(yes.state()).as("step 1: the name points here").isEqualTo("points_here");
            StateLineCell no = DomainParts.reachCell(awayRow, HostnameReach.LOOKUP_WAIT_MS);
            assertThat(no.state()).as("step 1: the other name points elsewhere").isEqualTo("points_elsewhere");
            assertThat(String.valueOf(no.detail().args().get("addresses")))
                .as("step 1: naming the address it points to")
                .isEqualTo(pointsAway.publicAddress().getHostAddress());
            assertThat(DomainParts.reachCell(pattern, HostnameReach.LOOKUP_WAIT_MS))
                .as("step 1: a pattern has no answer").isNull();

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
            assertThat(html).as("step 3: the App cell links the app to its overview, the framework's front door")
                .contains("class=\"cms-record-link\" href=\"/admin/" + HohenheimSlugs.SITES + "/"
                    + site.get(SiteModel.ID) + "/open\">addresses-" + suffix + "</a>");

            // 4. The HTTPS cell says why a name does not work: forced without a working certificate, and, for a name
            //    another server holds the certificate for (no "Get a certificate" there), that Let's Encrypt is told
            //    never to ask. The badge is the word, never the certificate's name, which links on its own line.
            assertThat(DomainParts.httpsDetail(awayRow, CertCoverage.ERROR, null).key())
                .as("step 4: a forced name without a certificate").isEqualTo("https_forced_uncovered");
            assertThat(DomainParts.httpsDetail(excluded, CertCoverage.ERROR, null).key())
                .as("step 4: a forced name Let's Encrypt may not ask for").isEqualTo("https_forced_excluded");
            assertThat(DomainParts.httpsDetail(hereRow, CertCoverage.NONE, null).key())
                .as("step 4: an unforced name without one serves plain HTTP").isEqualTo("https_uncovered");
            assertThat(DomainParts.httpsDetail(coveredRow, CertCoverage.ACTIVE, null))
                .as("step 4: a working name needs no reason").isNull();
            assertThat(html).as("step 4: the reason renders beside the broken badge")
                .contains("HTTPS is forced, but no working certificate covers this name");
            assertThat(html).as("step 4: the covered name's badge is the word").contains(">Works<");
            assertThat(html).as("step 4: and its certificate links on its own line")
                .contains("data-cert-link").contains("Covered " + suffix);

            // 5. The Domains area heads its tabs with its own name, and its fourth tab reads in the board's words.
            assertThat(html).as("step 5: the area's name above the tabs").contains("data-cms-cluster-title")
                .containsPattern("data-cms-cluster-title>(<!--[^>]*-->)?<pb-microcopy[^>]*>Domains</pb-microcopy>");
            assertThat(html).as("step 5: released names are addresses").contains("Released addresses");
            String strip = html.substring(html.indexOf("data-cms-cluster-tabs"));
            List<Integer> tabs = List.of(DomainParts.SLUG, HohenheimSlugs.CERTIFICATES, HohenheimSlugs.DNS_ZONES,
                ReleasedClaimParts.SLUG).stream().map(slug -> strip.indexOf("href=\"/admin/" + slug + "\"")).toList();
            assertThat(tabs).as("step 5: every member is a tab").doesNotContain(-1);
            assertThat(tabs).as("step 5: in the board's order: Addresses, Certificates, DNS zones, Released addresses")
                .isSorted();
            assertThat(html).as("step 5: the header button reads as the board's")
                .containsPattern(Pattern.compile("data-cms-create[^>]*>.{0,1000}?Add address", Pattern.DOTALL));

            // 6. A certificate's names read as a list, a space after each comma, though stored comma-joined.
            String certificates = adminGet("/admin/certificates?q=" + suffix).body();
            assertThat(certificates).as("step 6: the covered names, separated as words")
                .contains(covered + ", www." + covered);

            // 7. The address's own page heads with the same answers.
            HttpResponse<String> detail = adminGet("/admin/domains/" + hereRow.get(SiteDomainModel.ID));
            assertThat(detail.body()).as("step 7: the lead line names the app and says it points here")
                .contains("addresses-" + suffix).contains("points here: Yes");

            // 8. A certificate stored as working that the running proxy cannot serve (no material it could load) is
            //    no working certificate: the covered name reads "Not working" and says why, on the list and in the
            //    one per-name rule the Apps list and the dashboard read, never "Works".
            ProxyServer previous = ServerMain.getProxyServer();
            ProxyServer proxy = ProxyTestSupport.startProxy();
            ServerMain.adoptProxyServer(proxy);
            try {
                assertThat(AppHealth.workingNames()).as("step 8: the proxy serves nothing for the stored row")
                    .doesNotContain(covered);
                assertThat(AppHealth.httpsOf(coveredRow, false, AppHealth.workingNames()))
                    .as("step 8: the covered name does not work").isEqualTo(CertCoverage.ERROR);
                Row stored = CertificateCoverage.coveringCertificate(covered);
                assertThat(DomainParts.httpsDetail(coveredRow, CertCoverage.ERROR, stored).key())
                    .as("step 8: because the proxy cannot serve its certificate")
                    .isEqualTo("https_certificate_unserved");
                assertThat(DomainParts.httpsDetail(coveredRow, CertCoverage.ERROR, stored)
                        .resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                    .as("step 8: in Dutch too").contains("de proxy kan het niet aanbieden");
                String served = adminGet("/admin/domains?q=" + suffix).body();
                assertThat(served).as("step 8: the list says so beside the badge")
                    .contains("A certificate is stored for this name, but the proxy cannot serve it")
                    .doesNotContain("data-cert-status=\"" + CertCoverage.ACTIVE.key() + "\"");
            } finally {
                ServerMain.adoptProxyServer(previous);
                proxy.stop();
            }
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
