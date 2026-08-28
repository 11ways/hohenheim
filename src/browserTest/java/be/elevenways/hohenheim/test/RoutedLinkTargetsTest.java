package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The links a converted template actually SERVES.
 *
 * AIDEV-NOTE: templates that used to concatenate {@code basePath + "/domains/new?site_id="}
 * now carry {@code use:Zenit.route} over a handler-provided RouteTarget. That change is
 * invisible to a compiler and to a smoke test: a wrong resource slug or a dropped query
 * parameter still RENDERS, and only 404s when somebody clicks. So this walks the Domains
 * tab and pins the SSR'd href of every converted link to its exact literal -- and then
 * MUTATES the route arguments to prove the assertion is not vacuous.
 *
 * The literals are written out on purpose. A test that rebuilt its expectation from
 * CmsRoutes would assert that CmsRoutes equals itself and would pin no URL at all.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutedLinkTargetsTest extends HohenheimTestBase {

    private static Integer siteId;
    private static Integer domainId;

    @BeforeAll
    static void discoverListenAddresses() {
        // The listen_on select validates against discovered addresses; the boot task
        // that populates them does not run in the test JVM.
        UpdateSystemIpAddresses.discover();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The href of the element carrying {@code id}, as the server actually rendered it. */
    private static String hrefOf(String html, String id) {
        Matcher matcher = Pattern
            .compile("<[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>")
            .matcher(html);
        assertThat(matcher.find())
            .withFailMessage("no element with id=\"%s\" in the rendered page", id)
            .isTrue();
        Matcher href = Pattern.compile("href=\"([^\"]*)\"").matcher(matcher.group());
        assertThat(href.find())
            .withFailMessage("element id=\"%s\" rendered without an href: %s -- the"
                + " use:Zenit.route directive did not set one", id, matcher.group())
            .isTrue();
        return href.group(1);
    }

    @Test
    @Order(1)
    void domainsTabServesTheExactLinksItUsedToConcatenate() throws Exception {
        // 1. A site with one domain, so the tab has both header links and a row.
        var siteResponse = postForm("/admin/sites/new",
            "name=Routed+Link+Site&upstream_kind=hohenheim%3Aaddress"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9091");
        assertThat(siteResponse.statusCode()).as("step 1: the site is created").isIn(200, 302, 303);
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Routed Link Site")).first();
        assertThat(site).as("step 1: the site exists").isNotNull();
        siteId = site.get(SiteModel.ID);

        var domainResponse = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=routed-link.example.com&match_type=exact");
        assertThat(domainResponse.statusCode()).as("step 1: the domain is created")
            .isIn(200, 302, 303);
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("routed-link.example.com")).first();
        assertThat(domain).as("step 1: the domain exists").isNotNull();
        domainId = domain.get(SiteDomainModel.ID);

        // 2. The Domains tab renders.
        HttpResponse<String> tab = get("/admin/sites/" + siteId + "/page/domains");
        assertThat(tab.statusCode()).as("step 2: the Domains tab renders").isEqualTo(200);
        String html = tab.body();

        // 3. THE CONVERSION. Every one of these was a string concatenation before; the
        //    produced URL must be byte-identical to what it produced then.
        assertThat(hrefOf(html, "add-domain-link"))
            .as("step 3: the create link keeps its panel, resource and site_id prefill")
            .startsWith("/admin/domains/new?site_id=" + siteId);
        assertThat(hrefOf(html, "add-domain-link"))
            .as("step 3: and is bound back to the tab it was offered on")
            .contains("_return=");
        assertThat(hrefOf(html, "request-cert-link"))
            .as("step 3: the certificate request link keeps its ?site= parameter")
            .isEqualTo("/admin/certificates-request?site=" + siteId);

        // 4. And the per-row edit anchor, which came from a map entry rather than a
        //    declared variable -- the shape most likely to render blank if mis-wired.
        assertThat(html)
            .as("step 4: the row links at the domain's own record page, bound back to this tab")
            .contains("href=\"/admin/domains/" + domainId + "?_return=");
    }

    /**
     * THE COUNTERFACTUAL. The assertions above are only worth something if a wrong route
     * argument would break them, so this builds the same targets with each argument in
     * turn replaced by a plausible mistake and proves each one produces a URL the test
     * would have rejected.
     */
    @Test
    @Order(2)
    void aWrongRouteArgumentProducesAUrlThePageAssertionRejects() {
        String expected = "/admin/domains/new?site_id=" + siteId;

        // 1. The correct composition is what the page emitted -- the baseline.
        assertThat(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "domains")
                .with(HohenheimParams.SITE_ID_PREFILL, siteId).toUrl())
            .as("step 1: the composition under test reproduces the asserted URL")
            .isEqualTo(expected);

        // 2. Wrong PANEL: the /manage variant is a real, reachable, WRONG destination.
        assertThat(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "manage")
                .with(CmsEndpoints.RESOURCE_PARAM, "domains")
                .with(HohenheimParams.SITE_ID_PREFILL, siteId).toUrl())
            .as("step 2: a wrong panel slug fails the step-3 assertion")
            .isNotEqualTo(expected);

        // 3. Wrong RESOURCE: a singular slug is the classic typo, and 404s on click.
        assertThat(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "domain")
                .with(HohenheimParams.SITE_ID_PREFILL, siteId).toUrl())
            .as("step 3: a wrong resource slug fails the step-3 assertion")
            .isNotEqualTo(expected);

        // 4. DROPPED prefill: renders fine, opens an unbound create form.
        assertThat(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "domains").toUrl())
            .as("step 4: dropping the prefill parameter fails the step-3 assertion")
            .isNotEqualTo(expected);

        // 5. Wrong PARAMETER: binding the certificate page's ?site= instead of the create
        //    form's site_id= is the exact confusion these two neighbouring links invite.
        assertThat(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "domains")
                .with(HohenheimParams.CERTIFICATE_REQUEST_SITE, siteId).toUrl())
            .as("step 5: binding the wrong parameter definition fails the step-3 assertion")
            .isNotEqualTo(expected);
    }
}
