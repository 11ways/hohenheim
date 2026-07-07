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

    @Test
    @Order(6)
    void duplicateHostnameOnSameSiteIsRejected() throws Exception {
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact");

        long count = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("edit-test.example.com"))
            .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(7)
    void deleteRemovesTheDomain() throws Exception {
        var response = postForm("/admin/domains/" + domainId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).findById(domainId)).isNull();
    }
}
