package be.elevenways.hohenheim.test;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.model.Models;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

/**
 * Tests the full site lifecycle: create, view in list, edit, delete.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteLifecycleTest extends HohenheimTestBase {

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private void waitForTitle(String expected) {
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && expected.equals(el.textContent());
        });
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void createProxySite() throws Exception {
        var response = postForm("/sites/create",
            "name=Test+Backend&site_type=hohenheim%3Aproxy"
            + "&forward_host=127.0.0.1&forward_port=8080&hostname=test.example.com");

        assertThat(response.statusCode())
            .describedAs("POST returned %d, body: %s", response.statusCode(),
                response.body().substring(0, Math.min(500, response.body().length())))
            .isEqualTo(302);
    }

    @Test
    @Order(2)
    void siteAppearsInList() {
        navigateToApp("/sites");
        waitForHydration();

        assertThat(page.locator(".hh-site-link").count()).isGreaterThan(0);
        assertThat(page.locator(".hh-site-link").first().textContent()).isEqualTo("Test Backend");
    }

    @Test
    @Order(3)
    void dashboardShowsSiteCount() {
        navigateToApp("/");
        waitForHydration();

        String content = page.locator(".hh-stat-grid").textContent();
        assertThat(content).contains("Sites");
    }

    @Test
    @Order(4)
    void navigateToEditPage() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator(".hh-site-link").first().click();

        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Test Backend".equals(el.textContent());
        });

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Test Backend");
    }

    @Test
    @Order(5)
    void editPageShowsDomainAndDangerZone() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator(".hh-site-link").first().click();
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Test Backend".equals(el.textContent());
        });

        assertThat(page.locator("pl-card").count()).isGreaterThan(0);
        assertThat(page.locator("form").count()).isGreaterThan(0);

        String content = page.content();
        assertThat(content).contains("test.example.com");
        assertThat(content).contains("Danger Zone");
    }

    @Test
    @Order(6)
    void createStaticSite() throws Exception {
        var response = postForm("/sites/create",
            "name=Static+Files&site_type=hohenheim%3Astatic&root_path=%2Fvar%2Fwww%2Fstatic");

        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test
    @Order(7)
    void listShowsBothSites() {
        navigateToApp("/sites");
        waitForHydration();

        assertThat(page.locator(".hh-site-link").count()).isEqualTo(2);
    }

    @Test
    @Order(8)
    void deleteSite() throws Exception {
        navigateToApp("/sites");
        waitForHydration();

        // Get the site link's href to extract the ID
        String href = page.locator(".hh-site-link").first().getAttribute("href");
        assertThat(href).startsWith("/sites/");

        var response = postForm(href + "/delete", "");
        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test
    @Order(9)
    void listShowsOneLessSiteAfterDelete() {
        navigateToApp("/sites");
        waitForHydration();

        assertThat(page.locator(".hh-site-link").count()).isEqualTo(1);
    }

    @Test
    @Order(10)
    void createRedirectSite() throws Exception {
        var response = postForm("/sites/create",
            "name=Old+Domain&site_type=hohenheim%3Aredirect"
            + "&target_url=https%3A%2F%2Fexample.com&http_status=301"
            + "&hostname=old.example.com");

        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test
    @Order(11)
    void listShowsAllTypes() {
        navigateToApp("/sites");
        waitForHydration();

        // 1 remaining (static) + 1 new (redirect) = 2
        assertThat(page.locator(".hh-site-link").count()).isEqualTo(2);
    }

    /**
     * Exercises the schema-driven form extraction for nested sub-schemas (environment_variables
     * as List<Map>) and flat lists (api_keys as List<String>). The script field is left empty
     * so no child process is actually spawned.
     */
    @Test
    @Order(12)
    void createNodeSiteWithEnvVarsAndApiKeys() throws Exception {
        var response = postForm("/sites/create",
            "name=Node+App&site_type=hohenheim%3Anode"
            + "&script=&node_path=&user=&use_ports=on"
            + "&environment_variables%5B0%5D.name=NODE_ENV"
            + "&environment_variables%5B0%5D.value=production"
            + "&environment_variables%5B1%5D.name=PORT"
            + "&environment_variables%5B1%5D.value=3000"
            + "&api_keys%5B0%5D=alpha-key"
            + "&api_keys%5B1%5D=beta-key");

        assertThat(response.statusCode())
            .describedAs("POST returned %d, body: %s", response.statusCode(),
                response.body().substring(0, Math.min(500, response.body().length())))
            .isEqualTo(302);
    }

    @Test
    @Order(13)
    void nodeSiteEditShowsRoundTrippedEnvVarsAndApiKeys() {
        navigateToApp("/sites");
        waitForHydration();

        // Find the Node App row and click it
        var links = page.locator(".hh-site-link").all();
        String targetHref = null;
        for (var link : links) {
            if ("Node App".equals(link.textContent())) {
                targetHref = link.getAttribute("href");
                break;
            }
        }
        assertThat(targetHref).describedAs("Node App link should exist").isNotNull();

        navigateToApp(targetHref);
        waitForHydration();

        // The key-value editor should render one row per stored env var, with the
        // correct indexed form names. Use the name attribute to target them.
        String content = page.content();
        assertThat(content).contains("environment_variables[0].name");
        assertThat(content).contains("environment_variables[0].value");
        assertThat(content).contains("environment_variables[1].name");
        assertThat(content).contains("environment_variables[1].value");
        assertThat(content).contains("api_keys[0]");
        assertThat(content).contains("api_keys[1]");

        // And the values themselves should be present in the rendered inputs
        assertThat(page.locator("pl-input[name='environment_variables[0].name'] input").inputValue())
            .isEqualTo("NODE_ENV");
        assertThat(page.locator("pl-input[name='environment_variables[0].value'] input").inputValue())
            .isEqualTo("production");
        assertThat(page.locator("pl-input[name='environment_variables[1].name'] input").inputValue())
            .isEqualTo("PORT");
        assertThat(page.locator("pl-input[name='environment_variables[1].value'] input").inputValue())
            .isEqualTo("3000");
        assertThat(page.locator("pl-input[name='api_keys[0]'] input").inputValue())
            .isEqualTo("alpha-key");
        assertThat(page.locator("pl-input[name='api_keys[1]'] input").inputValue())
            .isEqualTo("beta-key");
    }

    /** Find the edit-page href of the site with the given name in the sites list. */
    private String siteHref(String name) {
        navigateToApp("/sites");
        waitForHydration();
        for (var link : page.locator(".hh-site-link").all()) {
            if (name.equals(link.textContent())) {
                return link.getAttribute("href");
            }
        }
        throw new AssertionError("No site named " + name + " in the list");
    }

    /**
     * The node form renders a use_ports control (default-checked) — before it existed, every
     * save materialized the absent checkbox to false and silently flipped sites to unix sockets.
     */
    @Test
    @Order(14)
    void nodeSiteEditShowsUsePortsCheckedByDefault() {
        navigateToApp(siteHref("Node App"));
        waitForHydration();

        assertThat(page.locator("#use_ports").count()).isEqualTo(1);
        assertThat(page.locator("#use_ports").isChecked()).isTrue();
        assertThat(page.locator("#wait_for_ready").isChecked()).isFalse();
    }

    @Test
    @Order(15)
    void nodeSiteUpdateRoundTripsUsePorts() throws Exception {
        String href = siteHref("Node App");

        // Save without the checkbox -> explicit false, edit page renders it unchecked.
        var response = postForm(href, "name=Node+App&site_type=hohenheim%3Anode&script=");
        assertThat(response.statusCode()).isEqualTo(302);
        navigateToApp(href);
        waitForHydration();
        assertThat(page.locator("#use_ports").isChecked()).isFalse();

        // Save with the checkbox -> back to true.
        response = postForm(href, "name=Node+App&site_type=hohenheim%3Anode&script=&use_ports=on");
        assertThat(response.statusCode()).isEqualTo(302);
        navigateToApp(href);
        waitForHydration();
        assertThat(page.locator("#use_ports").isChecked()).isTrue();
    }

    @Test
    @Order(16)
    void redirectSiteEditShowsPreservePath() throws Exception {
        String href = siteHref("Old Domain");

        navigateToApp(href);
        waitForHydration();
        assertThat(page.locator("#preserve_path").count()).isEqualTo(1);
        assertThat(page.locator("#preserve_path").isChecked()).isFalse();

        var response = postForm(href, "name=Old+Domain&site_type=hohenheim%3Aredirect"
            + "&target_url=https%3A%2F%2Fexample.com&http_status=301&preserve_path=on");
        assertThat(response.statusCode()).isEqualTo(302);

        navigateToApp(href);
        waitForHydration();
        assertThat(page.locator("#preserve_path").isChecked()).isTrue();
    }

    @Test
    @Order(17)
    void dockerSiteEditRoundTripsEnvVars() throws Exception {
        var response = postForm("/sites/create",
            "name=Docker+App&site_type=hohenheim%3Adocker&image=nginx"
            + "&environment_variables%5B0%5D.name=APP_MODE"
            + "&environment_variables%5B0%5D.value=staging");
        assertThat(response.statusCode()).isEqualTo(302);

        navigateToApp(siteHref("Docker App"));
        waitForHydration();

        assertThat(page.locator("pl-input[name='environment_variables[0].name'] input").inputValue())
            .isEqualTo("APP_MODE");
        assertThat(page.locator("pl-input[name='environment_variables[0].value'] input").inputValue())
            .isEqualTo("staging");
    }

    @Test
    @Order(18)
    void gitSourcedSiteEditRoundTripsBuildEnvVars() throws Exception {
        var response = postForm("/sites/create",
            "name=Git+App&site_type=hohenheim%3Astatic&root_path=%2Fvar%2Fwww%2Fgitapp"
            + "&source=git&repository_url=https%3A%2F%2Fexample.com%2Frepo.git"
            + "&build_environment_variables%5B0%5D.name=CI"
            + "&build_environment_variables%5B0%5D.value=true");
        assertThat(response.statusCode()).isEqualTo(302);

        navigateToApp(siteHref("Git App"));
        waitForHydration();

        assertThat(page.locator("pl-input[name='build_environment_variables[0].name'] input").inputValue())
            .isEqualTo("CI");
        assertThat(page.locator("pl-input[name='build_environment_variables[0].value'] input").inputValue())
            .isEqualTo("true");
    }

    /** The site form can attach an access list, and the choice persists + renders on edit. */
    @Test
    @Order(19)
    void siteFormAttachesAccessList() throws Exception {
        var response = postForm("/access-lists/create",
            "name=Office+Only&satisfy=any&allowed_ips=10.0.0.0%2F8");
        assertThat(response.statusCode()).isEqualTo(302);

        var accessListModel = Models.get(AccessListModel.class);
        var listRow = accessListModel.find().where(AccessListModel.NAME.eq("Office Only")).first();
        assertThat(listRow).isNotNull();
        Integer listId = listRow.get(AccessListModel.ID);

        String href = siteHref("Docker App");
        response = postForm(href, "name=Docker+App&site_type=hohenheim%3Adocker&image=nginx"
            + "&access_list_id=" + listId);
        assertThat(response.statusCode()).isEqualTo(302);

        var siteModel = Models.get(SiteModel.class);
        var siteRow = siteModel.find().where(SiteModel.NAME.eq("Docker App")).first();
        assertThat((Integer) siteRow.get(SiteModel.ACCESS_LIST_ID)).isEqualTo(listId);

        navigateToApp(href);
        waitForHydration();
        assertThat(page.locator("#access-list").count()).isEqualTo(1);
        assertThat(page.content()).contains("Office Only");
    }
}
