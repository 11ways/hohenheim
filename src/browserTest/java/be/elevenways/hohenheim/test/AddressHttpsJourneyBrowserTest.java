package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.RecordCodes;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.codeIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new address gets its certificate as the "Put online" wizard gives one, by address_set and by the panel's own
 * "Add address" form alike, through the wizard's orderer against the fake CA: ordered by default and then HTTPS works,
 * none for "Not now", each said in the toast or the answer; a person who manages the site but is no administrator
 * gets the address and no certificate, as the wizard gives him no address at all.
 *
 * AIDEV-NOTE: the proxy runs for real with HTTPS on a free port, so "works" is the handshake's own answer
 * (CertificateCoverage.handshakeNames); a first certificate is ordered before step 1 so HTTPS terminates from then
 * on. The local host declares no public address while this runs, so the order's reach pre-check refuses nothing.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AddressHttpsJourneyBrowserTest extends HohenheimTestBase {

    @Test
    void aNewAddressGetsTheWizardsCertificateByToolAndByPanel() throws Exception {
        String tag = HohenheimMcpTest.letters();
        String zone = tag + ".address-https.test";
        ProxyServer previous = ServerMain.getProxyServer();
        Integer httpsPort = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);
        Boolean letsEncrypt = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED);
        String directory = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        String email = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        Row local = Models.get(HostModel.class).findById(HostModel.localHostId());
        String ipv4 = local.get(HostModel.PUBLIC_IPV4);
        String ipv6 = local.get(HostModel.PUBLIC_IPV6);
        List<String> hostnames = new ArrayList<>();
        Row site = null;
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        ProxyServer proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        try (FakeAcmeServer ca = new FakeAcmeServer()) {
            ca.validateHttpWith((token, identifier) ->
                proxy.getAcmeService().getChallengeResponse(token, identifier) != null);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, ca.directoryUrl());
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, "acme-test@example.com");
            local.set(HostModel.PUBLIC_IPV4, null);
            local.set(HostModel.PUBLIC_IPV6, null);
            Models.get(HostModel.class).save(local);
            RateLimitMiddleware.limiter().clear();
            site = ProxyTestSupport.setupSite("hohenheim:static", "Address HTTPS " + tag, "address-https-" + tag,
                Map.<String, Object>of("root_path", "/tmp"));
            int siteId = site.get(SiteModel.ID);
            String siteCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(siteId)));
            String warm = "warm." + zone;
            hostnames.add(warm);
            ProxyTestSupport.addDomain(site, warm, "exact", null, false);
            assertThat(proxy.getAcmeService().requestCertificate(List.of(warm), warm, null,
                CertificateAuthority.Requester.SYSTEM).issued()).as("the first certificate").isTrue();
            proxy.reload();
            assertThat(proxy.isHttpsTerminationAvailable()).as("HTTPS terminates").isTrue();
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. address_set with the wizard's default: the certificate is ordered and the answer says HTTPS works,
            //    by the certificate's code.
            String byTool = "tool." + zone;
            hostnames.add(byTool);
            McpHttpSession.Answer ordered = admin.call("address_set", Map.of("site", siteCode, "hostname", byTool));
            assertThat(ordered.error()).as("step 1: " + ordered.text()).isFalse();
            assertThat(ordered.text()).as("step 1: HTTPS works").containsPattern("HTTPS works: certificate C\\d+ covers"
                + " it\\.");
            Row toolCert = CertificateCoverage.coveringCertificate(byTool);
            assertThat(toolCert).as("step 1: the certificate exists").isNotNull();
            assertThat((Object) toolCert.get(CertificateModel.STATUS)).as("step 1: and works")
                .isEqualTo(CertificateModel.STATUS_ACTIVE);

            // 2. The panel's "Add address" form shows the wizard's certificate choice, Automatic first: added so, its
            //    toast says the address is added and HTTPS works, by the certificate's name.
            String byPanel = "panel." + zone;
            hostnames.add(byPanel);
            navigateToApp(CmsRoutes.createUnder(HohenheimSlugs.ADMIN, HohenheimSlugs.DOMAINS, siteId).toUrl());
            waitForHydration();
            assertThat(page.locator("[name='https']").count()).as("step 2: the certificate choice").isPositive();
            assertThat(page.content()).as("step 2: in the wizard's words, Automatic chosen")
                .contains("Automatic (Let").contains("Not now");
            page.locator("pl-input[name='" + SiteDomainModel.HOSTNAME.getInputName() + "'] input").fill(byPanel);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/address-https-create.png"))
                .setFullPage(true));
            page.locator(".cms-form-actions pl-button[type='submit']").click();
            String toast = "pl-toast[data-flash-toast][open]";
            page.waitForSelector(toast, new Page.WaitForSelectorOptions().setTimeout(30_000));
            assertThat(page.locator(toast).textContent()).as("step 2: the toast")
                .contains("Address " + byPanel + " is added.")
                .contains("HTTPS works: certificate " + byPanel + " covers it.");

            // 3. "Not now": added without a certificate, nothing asked of the CA, and the toast says how to get one,
            //    in both languages.
            String later = "later." + zone;
            hostnames.add(later);
            int asked = ca.requests().size();
            HttpResponse<String> made = adminPostForm(CmsRoutes.createUnder(HohenheimSlugs.ADMIN,
                    HohenheimSlugs.DOMAINS, siteId).toUrl(),
                SiteDomainModel.SITE_ID.getInputName() + "=" + siteId + "&"
                    + SiteDomainModel.HOSTNAME.getInputName() + "=" + URLEncoder.encode(later, StandardCharsets.UTF_8)
                    + "&https=later&" + PanelResourceCalls.createEnvelope());
            assertThat(made.statusCode()).as("step 3: the create lands").isIn(302, 303);
            FlashNotice none = popFlash(made);
            assertThat(none).as("step 3: a toast").isNotNull();
            assertThat(toastText(none, "en")).as("step 3: in English")
                .isEqualTo("Address " + later + " is added. It has no certificate yet: use Get a certificate on its"
                    + " row.");
            assertThat(toastText(none, "nl")).as("step 3: in Dutch")
                .isEqualTo("Adres " + later + " is toegevoegd. Het heeft nog geen certificaat: gebruik Certificaat"
                    + " aanvragen op de rij.");
            assertThat(CertificateCoverage.coveringCertificate(later)).as("step 3: no certificate").isNull();
            assertThat(ca.requests()).as("step 3: the CA was not asked").hasSize(asked);

            // 4. A person who manages the site but is no administrator adds an address by tool: it is made, no
            //    certificate is ordered (certificates are installation administration, and the wizard gives him no
            //    address at all), and the answer says so in plain words; the choice and the order are refused him.
            UUID managerId = ApiSupport.user("address-https-" + tag + "@surface.test", "Address Manager");
            RecordGrants.grant(GrantSubjectType.USER, managerId, SiteModel.MODEL_ID, siteId,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession manager = HohenheimMcpTest.presetSession(server, endpoint, managerId, "Address Manager");
            String his = "his." + zone;
            hostnames.add(his);
            asked = ca.requests().size();
            McpHttpSession.Answer added = manager.call("address_set", Map.of("site", siteCode, "hostname", his));
            assertThat(added.error()).as("step 4: " + added.text()).isFalse();
            assertThat(added.text()).as("step 4: no certificate, said plainly")
                .contains("It has no certificate yet: an administrator gets one for it.");
            Row hisAddress = Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq(his)).first();
            assertThat(hisAddress).as("step 4: the address is his").isNotNull();
            assertThat(CertificateCoverage.coveringCertificate(his)).as("step 4: no certificate").isNull();
            assertThat(ca.requests()).as("step 4: the CA was not asked").hasSize(asked);
            McpHttpSession.Answer chosen = manager.call("address_set", Map.of("site", siteCode, "hostname",
                "chosen." + zone, "https", "later"));
            assertThat(chosen.error()).as("step 4: the choice is an administrator's " + chosen.text()).isTrue();
            assertThat(chosen.text()).as("step 4: named").contains("https");
            McpHttpSession.Answer order = manager.call("certificate_order", Map.of("address", codeIn(added.text(),
                "D")));
            assertThat(order.error()).as("step 4: no order of his own " + order.text()).isTrue();
            assertThat(ca.requests()).as("step 4: still nothing asked").hasSize(asked);
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, letsEncrypt);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, directory);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, email);
            Row restored = Models.get(HostModel.class).findById(HostModel.localHostId());
            restored.set(HostModel.PUBLIC_IPV4, ipv4);
            restored.set(HostModel.PUBLIC_IPV6, ipv6);
            Models.get(HostModel.class).save(restored);
            for (String hostname : hostnames) {
                Row cert = CertificateCoverage.coveringCertificate(hostname);
                if (cert != null) HardDeletes.byId(Models.get(CertificateModel.class), cert.get(CertificateModel.ID));
            }
            if (site != null) HardDeletes.byId(Models.get(SiteModel.class), site.get(SiteModel.ID));
            ServerMain.adoptProxyServer(previous);
            proxy.stop();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, httpsPort);
        }
    }
}
