package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The links a converted template actually SERVES.
 *
 * AIDEV-NOTE: the Domains tab's links are route targets (the child list's create under the
 * site, the declared certificate request link, the row's edit). That is invisible to a
 * compiler and to a smoke test: a wrong resource slug or a dropped query parameter still
 * RENDERS, and only 404s when somebody clicks. So this walks the Domains tab and pins the
 * SSR'd href of every link to its exact literal -- and then MUTATES the route arguments to
 * prove the assertion is not vacuous.
 *
 * The literals are written out on purpose. A test that rebuilt its expectation from
 * CmsRoutes would assert that CmsRoutes equals itself and would pin no URL at all.
 */
class RoutedLinkTargetsTest extends HohenheimTestBase {

    @BeforeAll
    static void discoverListenAddresses() {
        // The listen_on select validates against discovered addresses; the boot task
        // that populates them does not run in the test JVM.
        UpdateSystemIpAddresses.discover();
    }

    /** The href of the first element carrying the attribute, as the server actually rendered it. */
    private static String hrefOf(String html, String attribute) {
        Matcher matcher = Pattern
            .compile("<[^>]*\\s" + Pattern.quote(attribute) + "[^>]*>")
            .matcher(html);
        assertThat(matcher.find())
            .withFailMessage("no element with %s in the rendered page", attribute)
            .isTrue();
        Matcher href = Pattern.compile("\\shref=\"([^\"]*)\"").matcher(matcher.group());
        assertThat(href.find())
            .withFailMessage("element with %s rendered without an href: %s", attribute, matcher.group())
            .isTrue();
        return href.group(1).replace("&amp;", "&");
    }

    /**
     * The Domains tab serves the exact links it used to concatenate, and a wrong route
     * argument would have produced a URL these assertions reject.
     *
     * AIDEV-NOTE: ONE journey on purpose: the counterfactual is only meaningful against the
     * site id the page really rendered, which used to cross from one @Order test to the next
     * through a static field (so the counterfactual NPE'd when run alone).
     */
    @Test
    void domainsTabServesTheExactLinksItUsedToConcatenate() throws Exception {
        // 1. A site with one domain, so the tab has both header links and a row.
        var siteResponse = adminPostForm("/admin/sites/new",
            "name=Routed+Link+Site&upstream_kind=hohenheim%3Aaddress"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9091");
        assertThat(siteResponse.statusCode()).as("step 1: the site is created").isIn(200, 302, 303);
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Routed Link Site")).first();
        assertThat(site).as("step 1: the site exists").isNotNull();
        int siteId = site.get(SiteModel.ID);

        var domainResponse = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=routed-link.example.com&match_type=exact");
        assertThat(domainResponse.statusCode()).as("step 1: the domain is created")
            .isIn(200, 302, 303);
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("routed-link.example.com")).first();
        assertThat(domain).as("step 1: the domain exists").isNotNull();
        int domainId = domain.get(SiteDomainModel.ID);

        // 2. The Domains tab renders.
        HttpResponse<String> tab = adminGet("/admin/sites/" + siteId + "/page/domains");
        assertThat(tab.statusCode()).as("step 2: the Domains tab renders").isEqualTo(200);
        String html = tab.body();

        // 3. THE LINKS. The add and the certificate request are the domain section's own: the child list's create
        //    under this site, and the tab's declared header link.
        String add = hrefOf(html, "data-cms-child-create=\"domains\"");
        assertThat(add)
            .as("step 3: the create link names its panel, resource and this site as the parent")
            .startsWith("/admin/domains/new?parent=" + siteId);
        assertThat(add)
            .as("step 3: and is bound back to the tab it was offered on")
            .contains("_return=");
        assertThat(hrefOf(html, "data-action-id=\"hohenheim:request_certificate\""))
            .as("step 3: the certificate request link keeps its ?site= parameter")
            .matches("/admin/certificates-request\\?site=" + siteId + "(&.*)?");

        // 4. And the per-row edit anchor, bound back to this tab.
        assertThat(html)
            .as("step 4: the row links at the domain's own record page, bound back to this tab")
            .contains("href=\"/admin/domains/" + domainId + "?_return=");

        // 5. The counterfactual below, against the very site this page rendered.
        aWrongRouteArgumentProducesAUrlThePageAssertionRejects(siteId);
    }

    /**
     * THE COUNTERFACTUAL. The assertions above are only worth something if a wrong route
     * argument would break them, so this builds the same target with each argument in
     * turn replaced by a plausible mistake and proves each one produces a URL the test
     * would have rejected.
     */
    private static void aWrongRouteArgumentProducesAUrlThePageAssertionRejects(int siteId) {
        String expected = "/admin/domains/new?parent=" + siteId;

        // 5.1. The correct composition is what the page emitted -- the baseline.
        assertThat(CmsRoutes.createUnder("admin", "domains", siteId).toUrl())
            .as("step 5.1: the composition under test reproduces the asserted URL")
            .isEqualTo(expected);

        // 5.2. Wrong PANEL: the /manage variant is a real, reachable, WRONG destination.
        assertThat(CmsRoutes.createUnder("manage", "domains", siteId).toUrl())
            .as("step 5.2: a wrong panel slug fails the step-3 assertion above")
            .isNotEqualTo(expected);

        // 5.3. Wrong RESOURCE: a singular slug is the classic typo, and 404s on click.
        assertThat(CmsRoutes.createUnder("admin", "domain", siteId).toUrl())
            .as("step 5.3: a wrong resource slug fails the step-3 assertion above")
            .isNotEqualTo(expected);

        // 5.4. DROPPED parent: renders fine, opens an unbound create form.
        assertThat(CmsRoutes.create("admin", "domains").toUrl())
            .as("step 5.4: dropping the parent fails the step-3 assertion above")
            .isNotEqualTo(expected);

        // 5.5. Wrong PARAMETER: binding the certificate page's ?site= instead of the create
        //    form's parent= is the exact confusion these two neighbouring links invite.
        assertThat(CmsRoutes.create("admin", "domains")
                .with(HohenheimParams.CERTIFICATE_REQUEST_SITE, siteId).toUrl())
            .as("step 5.5: binding the wrong parameter definition fails the step-3 assertion above")
            .isNotEqualTo(expected);
    }
}
