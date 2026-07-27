package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Domain CRUD through the (nav-hidden) zenit-cms domain resource: relation
 * pick to the site, header maps, uniqueness validation, and the site's
 * Domains tab.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DomainEditTest extends HohenheimTestBase {

    @BeforeAll
    static void discoverListenAddresses() {
        // The listen_on select validates against discovered addresses; the boot
        // task that populates them does not run in the test JVM.
        UpdateSystemIpAddresses.discover();
    }

    private static Integer siteId;
    private static Integer domainId;

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

    @Test
    @Order(1)
    void createSiteAndDomain() throws Exception {
        var siteResponse = postForm("/admin/sites/new",
            "name=Domain+Test+Site&site_type=hohenheim%3Aproxy&source=local"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9090");
        assertThat(siteResponse.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Domain Test Site")).first();
        assertThat(site).isNotNull();
        siteId = site.get(SiteModel.ID);

        navigateToApp("/admin/domains/new?site_id=" + siteId);
        waitForHydration();
        assertThat(page.locator("pl-select[name='match_type'] .pl-select-value")
            .textContent().trim()).isEqualTo("Exact hostname");
        assertThat(page.locator("pl-switch[name='force_ssl']").getAttribute("checked"))
            .isNotNull();

        var domainResponse = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact");
        assertThat(domainResponse.statusCode()).isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("edit-test.example.com")).first();
        assertThat(domain).isNotNull();
        assertThat((Integer) domain.get(SiteDomainModel.SITE_ID)).isEqualTo(siteId);
        domainId = domain.get(SiteDomainModel.ID);
    }

    @Test
    @Order(2)
    void domainAppearsOnSiteDomainsTab() {
        navigateToApp("/admin/sites/" + siteId + "/page/domains");
        waitForHydration();

        assertThat(page.locator("body").textContent()).contains("edit-test.example.com");
        assertThat(page.locator("a[href='/admin/domains/" + domainId + "']").count()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void editFormRendersTheDomain() {
        navigateToApp("/admin/domains/" + domainId);
        waitForHydration();

        assertThat(page.content()).contains("edit-test.example.com");
        assertThat(page.locator("form").count()).isGreaterThan(0);

        // The child record page breadcrumbs back to the owning site's Domains
        // tab, and record titles render as literal text -- never as microcopy
        // keys (user data must not enter the translation pipeline).
        var crumbs = page.locator(".cms-breadcrumbs");
        assertThat(crumbs.count()).isEqualTo(1);
        assertThat(crumbs.textContent()).contains("Domain Test Site");
        assertThat(crumbs.textContent()).contains("edit-test.example.com");
        assertThat(page.locator(".cms-breadcrumbs a[href='/admin/sites/" + siteId
            + "/page/domains']").count()).isEqualTo(1);
        assertThat(page.locator(".cms-breadcrumbs zn-microcopy[key='Domain Test Site']").count())
            .isEqualTo(0);
    }

    @Test
    @Order(4)
    void updateDomainSettingsIncludingHeaderMaps() throws Exception {
        var response = postForm("/admin/domains/" + domainId,
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=wildcard"
            + "&force_ssl=true&hsts_enabled=true"
            + "&path=%2Fapp&strip_path=true&listen_on=127.0.0.1"
            + "&custom_headers.0.key=X-Injected&custom_headers.0.value=yes"
            + "&response_headers.0.key=X-Strip-Me&response_headers.0.value=");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).findById(domainId);
        assertThat((String) domain.get(SiteDomainModel.MATCH_TYPE)).isEqualTo("wildcard");
        assertThat((Boolean) domain.get(SiteDomainModel.FORCE_SSL)).isEqualTo(true);
        assertThat((Boolean) domain.get(SiteDomainModel.HSTS_ENABLED)).isEqualTo(true);
        assertThat((String) domain.get(SiteDomainModel.PATH)).isEqualTo("/app");
        assertThat((String) domain.get(SiteDomainModel.LISTEN_ON)).isEqualTo("127.0.0.1");

        Map<String, String> headers = domain.get(SiteDomainModel.CUSTOM_HEADERS);
        assertThat(headers).containsEntry("X-Injected", "yes");
        Map<String, String> responseHeaders = domain.get(SiteDomainModel.RESPONSE_HEADERS);
        assertThat(responseHeaders).containsEntry("X-Strip-Me", "");
    }

    @Test
    @Order(5)
    void blankHostnameIsRejected() throws Exception {
        postForm("/admin/domains/" + domainId,
            "site_id=" + siteId + "&hostname=&match_type=exact");

        Row domain = Models.get(SiteDomainModel.class).findById(domainId);
        assertThat((String) domain.get(SiteDomainModel.HOSTNAME))
            .as("a blank hostname must not overwrite the stored one")
            .isEqualTo("edit-test.example.com");
    }

    /** The stored domain is edit-test.example.com on path /app by this point. */
    @Test
    @Order(6)
    void duplicateHostnameAndPathOnSameSiteIsRejected() throws Exception {
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact&path=%2Fapp");

        assertThat(domainsNamed("edit-test.example.com"))
            .as("the same hostname on the same path is one route, so the second row is refused")
            .isEqualTo(1);
    }

    /**
     * Uniqueness must compare CANONICAL paths: "app", "/app" and "/app/" are one
     * route to the dispatcher, so the editor has to refuse all three as duplicates.
     */
    @Test
    @Order(7)
    void duplicatePathIsRejectedRegardlessOfSlashSpelling() throws Exception {
        for (String spelling : new String[] {"app", "%2Fapp%2F", "+%2Fapp+"}) {
            postForm("/admin/domains/new",
                "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact"
                + "&path=" + spelling);

            assertThat(domainsNamed("edit-test.example.com"))
                .as("path spelling '" + spelling + "' canonicalizes to /app and must be refused")
                .isEqualTo(1);
        }
    }

    /**
     * The dispatcher routes host+path pairs, so one hostname may legitimately fan out
     * over several paths on the same site (how the NetBird gRPC/API split is configured).
     */
    @Test
    @Order(8)
    void sameHostnameOnADifferentPathIsAccepted() throws Exception {
        var response = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact"
            + "&path=%2Fmanagement.ProxyService");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        assertThat(domainsNamed("edit-test.example.com"))
            .as("a different path is a different route and must be accepted")
            .isEqualTo(2);

        Row added = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("edit-test.example.com"))
            .where(SiteDomainModel.PATH.eq("/management.ProxyService"))
            .first();
        assertThat(added).isNotNull();
    }

    /** A catch-all row alongside path rows is also a distinct route. */
    @Test
    @Order(9)
    void catchAllAlongsidePathRowsIsAccepted() throws Exception {
        var response = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact&path=");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        assertThat(domainsNamed("edit-test.example.com")).isEqualTo(3);

        // ...but only once: blank and "/" are the same catch-all route.
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact&path=%2F");
        assertThat(domainsNamed("edit-test.example.com"))
            .as("blank and \"/\" are the same catch-all route")
            .isEqualTo(3);
    }

    /** Editing a row must not treat the row's own stored path as a conflict. */
    @Test
    @Order(10)
    void updatingARowKeepingItsPathIsAllowed() throws Exception {
        var response = postForm("/admin/domains/" + domainId,
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=wildcard"
            + "&path=%2Fapp&force_ssl=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).findById(domainId);
        assertThat((String) domain.get(SiteDomainModel.PATH)).isEqualTo("/app");
    }

    /** Moving a row onto a sibling's path is still a conflict. */
    @Test
    @Order(11)
    void updatingARowOntoASiblingPathIsRejected() throws Exception {
        postForm("/admin/domains/" + domainId,
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact"
            + "&path=%2Fmanagement.ProxyService");

        Row domain = Models.get(SiteDomainModel.class).findById(domainId);
        assertThat((String) domain.get(SiteDomainModel.PATH))
            .as("the conflicting update must not be persisted")
            .isEqualTo("/app");
    }

    @Test
    @Order(12)
    void deleteRemovesTheDomain() throws Exception {
        var response = postForm("/admin/domains/" + domainId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).findById(domainId)).isNull();
    }

    private static long domainsNamed(String hostname) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname))
            .count();
    }
}
