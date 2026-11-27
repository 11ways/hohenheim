package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.HohenheimMcpTest;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.flash.FlashNotice;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way to order a certificate spends one hourly budget of the caller's: the certificate list's order, an
 * address row's, address_set, the panel's "Add address" form and the "Put online" wizard. Once it is spent, the orders
 * are refused, while an address is still made and its answer, toast or outcome says why it has no certificate.
 *
 * AIDEV-NOTE: the fake CA stands in for Let's Encrypt only so that an order the budget failed to stop would show in
 * its trace; the five spending orders are refused for their email after spending, so the CA is never asked at all.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class CertificateOrderBudgetJourneyTest extends HohenheimHttpTestBase {

    /** What an answer says when the budget, and nothing else, kept the certificate from being ordered. */
    private static final String SPENT = "You have ordered as many certificates as an hour allows.";

    @Test
    void everyOrderPathSharesTheCallersHourAndAnAddressIsStillMade() throws Exception {
        String tag = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        String zone = tag + ".order-budget.test";
        Boolean letsEncrypt = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED);
        String directory = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        List<String> hostnames = new ArrayList<>();
        List<Integer> sites = new ArrayList<>();
        Row local = Models.get(HostModel.class).findById(HostModel.localHostId());
        String ipv4 = local.get(HostModel.PUBLIC_IPV4);
        String ipv6 = local.get(HostModel.PUBLIC_IPV6);
        try (FakeAcmeServer ca = new FakeAcmeServer()) {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, ca.directoryUrl());
            RateLimitMiddleware.limiter().clear();
            // The local host declares no public address, so no name here is refused for where it points: the budget
            // is the one reason left.
            local.set(HostModel.PUBLIC_IPV4, null);
            local.set(HostModel.PUBLIC_IPV6, null);
            Models.get(HostModel.class).save(local);
            Row site = ProxyTestSupport.setupSite("hohenheim:static", "Order budget " + tag, "order-budget-" + tag,
                Map.<String, Object>of("root_path", "/tmp"));
            int siteId = site.get(SiteModel.ID);
            sites.add(siteId);
            String siteCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(siteId)));
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. The hour's orders, spent by the certificate list's order: each is refused for its email, never yet
            //    for the budget.
            for (int order = 1; order <= HohenheimEndpoints.LE_REQUEST_LIMIT.requests(); order++) {
                McpHttpSession.Answer refused = admin.call("certificate_order", Map.of("hostnames",
                    List.of("spend" + order + "." + zone), "email", "not-an-email"));
                assertThat(refused.error()).as("step 1: order " + order + " is refused " + refused.text()).isTrue();
                assertThat(refused.text()).as("step 1: for its email").contains("Invalid account email");
            }

            // 2. The next order from the certificate list is refused, and nothing is written.
            String sixth = "sixth." + zone;
            hostnames.add(sixth);
            McpHttpSession.Answer listed = admin.call("certificate_order", Map.of("hostnames", List.of(sixth)));
            assertThat(listed.error()).as("step 2: refused " + listed.text()).isTrue();
            assertThat(listed.text()).as("step 2: for the budget, not the email").doesNotContain("email");
            assertThat(CertificateCoverage.coveringCertificate(sixth)).as("step 2: no certificate").isNull();

            // 3. address_set with the wizard's default: the address is made, and the answer says the hour's orders
            //    are spent.
            String byTool = "tool." + zone;
            hostnames.add(byTool);
            McpHttpSession.Answer made = admin.call("address_set", Map.of("site", siteCode, "hostname", byTool));
            assertThat(made.error()).as("step 3: " + made.text()).isFalse();
            Row toolAddress = Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.HOSTNAME.eq(byTool)).first();
            assertThat(toolAddress).as("step 3: the address is made").isNotNull();
            assertThat(made.text()).as("step 3: why it has no certificate")
                .contains("It has no certificate yet. " + SPENT);
            assertThat(CertificateCoverage.coveringCertificate(byTool)).as("step 3: no certificate").isNull();

            // 4. The address row's order for that address is refused as well.
            String addressCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteDomainModel.MODEL_ID,
                String.valueOf((Object) toolAddress.get(SiteDomainModel.ID))));
            McpHttpSession.Answer row = admin.call("certificate_order", Map.of("address", addressCode));
            assertThat(row.error()).as("step 4: refused " + row.text()).isTrue();
            assertThat(CertificateCoverage.coveringCertificate(byTool)).as("step 4: still none").isNull();

            // 5. The panel's "Add address" form, Automatic: the address is added and its toast says why, in both
            //    languages.
            String byPanel = "panel." + zone;
            hostnames.add(byPanel);
            HttpResponse<String> added = adminPostForm(CmsRoutes.createUnder(HohenheimSlugs.ADMIN,
                    HohenheimSlugs.DOMAINS, siteId).toUrl(),
                SiteDomainModel.SITE_ID.getInputName() + "=" + siteId + "&"
                    + SiteDomainModel.HOSTNAME.getInputName() + "=" + URLEncoder.encode(byPanel, StandardCharsets.UTF_8)
                    + "&https=automatic&" + PanelResourceCalls.createEnvelope());
            assertThat(added.statusCode()).as("step 5: the create lands").isIn(302, 303);
            FlashNotice toast = popFlash(added);
            assertThat(toast).as("step 5: a toast").isNotNull();
            assertThat(toastText(toast, "en")).as("step 5: in English")
                .isEqualTo("Address " + byPanel + " is added. It has no certificate yet. " + SPENT
                    + " Once that is solved, use Get a certificate on its row.");
            assertThat(toastText(toast, "nl")).as("step 5: in Dutch")
                .contains("U hebt zoveel certificaten besteld als een uur toelaat.");
            assertThat(Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq(byPanel)).first())
                .as("step 5: the address is made").isNotNull();
            assertThat(CertificateCoverage.coveringCertificate(byPanel)).as("step 5: no certificate").isNull();

            // 6. "Put online" with an automatic certificate: the website goes live over HTTP, and its outcome says the
            //    hour's orders are spent.
            String byWizard = "wizard." + zone;
            hostnames.add(byWizard);
            AccessContext operator = TenantConduits.operator();
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(SiteModel.NAME.getName(), "budget-" + tag);
            form.put(PutOnline.HOSTNAME.getName(), byWizard);
            form.put(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect");
            form.put(SiteModel.SETTINGS.getName(), Map.of("target_url", "https://target.example/", "http_status",
                "301"));
            form.put(PutOnline.HTTPS.getName(), "automatic");
            OperationRun run = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_ADDRESS_ONLINE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                    .form(form))), operator, "step 6");
            Row wizardSite = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("budget-" + tag)).first();
            if (wizardSite != null) sites.add(wizardSite.get(SiteModel.ID));
            assertThat(run.status()).as("step 6: the run succeeded (%s)", run.outcome())
                .isEqualTo(OperationRunStatus.SUCCEEDED);
            assertThat(Objects.requireNonNull(run.outcome()).key()).as("step 6: its outcome says why")
                .isEqualTo("outcome_https_limited_note");
            assertThat(CertificateCoverage.coveringCertificate(byWizard)).as("step 6: no certificate").isNull();

            // 7. THE ASSERTION: no path reached the certificate authority.
            assertThat(ca.requests()).as("step 7: the CA was never asked").isEmpty();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, letsEncrypt);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, directory);
            RateLimitMiddleware.limiter().clear();
            Row restored = Models.get(HostModel.class).findById(HostModel.localHostId());
            restored.set(HostModel.PUBLIC_IPV4, ipv4);
            restored.set(HostModel.PUBLIC_IPV6, ipv6);
            Models.get(HostModel.class).save(restored);
            for (String hostname : hostnames) {
                Row cert = CertificateCoverage.coveringCertificate(hostname);
                if (cert != null) HardDeletes.byId(Models.get(CertificateModel.class), cert.get(CertificateModel.ID));
            }
            for (Integer siteId : sites) HardDeletes.byId(Models.get(SiteModel.class), siteId);
        }
    }
}
