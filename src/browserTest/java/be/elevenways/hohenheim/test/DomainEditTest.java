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
            "name=Domain+Test+Site&upstream_kind=hohenheim%3Aaddress"
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

    /**
     * The dispatcher lowercases non-regex hostnames into one exact bucket, so a
     * case-variant spelling is the SAME route and must be refused -- the model hook
     * lowercases on save, and comparing raw input used to slip past the check.
     */
    @Test
    @Order(12)
    void caseVariantHostnameIsRejected() throws Exception {
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=Edit-Test.EXAMPLE.com&match_type=exact&path=%2Fapp");

        assertThat(domainsNamed("edit-test.example.com"))
            .as("a case-variant hostname collapses to the same route and must be refused")
            .isEqualTo(3);
    }

    /**
     * normalizeRoutePath must be a fixpoint: "//" canonicalizes to the catch-all
     * (null) exactly like "/" -- it used to canonicalize to "/", which the editor
     * accepted as a distinct row and the route build then collapsed first-wins.
     */
    @Test
    @Order(13)
    void doubleSlashIsTheSameCatchAll() throws Exception {
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=edit-test.example.com&match_type=exact&path=%2F%2F");

        assertThat(domainsNamed("edit-test.example.com"))
            .as("\"//\" is the same catch-all route as blank and \"/\"")
            .isEqualTo(3);
    }

    /**
     * Listener restrictions are part of route identity: two rows sharing host+path
     * are distinct only while their listener sets cannot overlap. Uses its own
     * hostname so no earlier ordered test's listen_on state leaks in.
     */
    @Test
    @Order(14)
    void listenAddressesDecideRouteIdentity() throws Exception {
        String host = "listen-test.example.com";

        var first = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=127.0.0.1");
        assertThat(first.statusCode()).isIn(200, 302, 303);
        assertThat(domainsNamed(host)).isEqualTo(1);

        // ::1 always exists in the discovered set (the listen_on select validates
        // against it), so the disjoint case does not depend on this machine's NICs.
        var second = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=%3A%3A1");
        assertThat(second.statusCode()).isIn(200, 302, 303);
        assertThat(domainsNamed(host))
            .as("a disjoint listen_on set is a distinct route")
            .isEqualTo(2);

        // Same address as the first row: one route, so the row must be refused.
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=127.0.0.1");
        assertThat(domainsNamed(host))
            .as("an overlapping listen_on set is the same route")
            .isEqualTo(2);

        // An UNRESTRICTED row listens everywhere, so it overlaps both restricted ones.
        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Fapp");
        assertThat(domainsNamed(host))
            .as("an unrestricted listener set overlaps every restricted one")
            .isEqualTo(2);
    }

    /**
     * normalizeRoutePath must be a TRUE fixpoint: "/fx /" (trailing space exposed
     * by the trailing-slash strip) canonicalizes to "/fx", is STORED canonical,
     * and a later "/fx" row is the same route.
     */
    @Test
    @Order(15)
    void trailingWhitespacePathCanonicalizesToTheSameRoute() throws Exception {
        String host = "fixpoint-test.example.com";
        var first = postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Ffx%20%2F");
        assertThat(first.statusCode()).isIn(200, 302, 303);
        Row stored = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(host)).first();
        assertThat(stored).isNotNull();
        assertThat((String) stored.get(SiteDomainModel.PATH))
            .as("the canonical path is stored, residue like \"/fx \" never routes")
            .isEqualTo("/fx");

        postForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Ffx");
        assertThat(domainsNamed(host))
            .as("\"/fx /\" and \"/fx\" are one route")
            .isEqualTo(1);
    }

    /**
     * Route identity is GLOBAL: the dispatcher's table spans every enabled site and
     * silently drops the loser of a duplicate claim, so the editor must refuse the
     * same route on another ENABLED site while exempting disabled ones (clones,
     * staged drafts) until they are enabled.
     */
    @Test
    @Order(16)
    void sameRouteOnAnotherEnabledSiteIsRejected() throws Exception {
        var siteResponse = postForm("/admin/sites/new",
            "name=Second+Route+Site&upstream_kind=hohenheim%3Aaddress"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9091");
        assertThat(siteResponse.statusCode()).isIn(200, 302, 303);
        Row second = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Second Route Site")).first();
        assertThat(second).isNotNull();

        postForm("/admin/domains/new",
            "site_id=" + second.get(SiteModel.ID)
            + "&hostname=edit-test.example.com&match_type=exact");
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(second.get(SiteModel.ID))).count())
            .as("the same host+path on another enabled site is one route and must be refused")
            .isEqualTo(0);
    }

    @Test
    @Order(17)
    void disabledSiteRowsAreExemptUntilEnabling() throws Exception {
        var siteResponse = postForm("/admin/sites/new",
            "name=Draft+Route+Site&upstream_kind=hohenheim%3Aaddress"
            + "&enabled=false&settings.forward_host=127.0.0.1&settings.forward_port=9092");
        assertThat(siteResponse.statusCode()).isIn(200, 302, 303);
        Row draft = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Draft Route Site")).first();
        assertThat(draft).isNotNull();
        assertThat((Boolean) draft.get(SiteModel.ENABLED)).isFalse();
        Integer draftId = draft.get(SiteModel.ID);

        var domainResponse = postForm("/admin/domains/new",
            "site_id=" + draftId + "&hostname=edit-test.example.com&match_type=exact");
        assertThat(domainResponse.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(draftId)).count())
            .as("a DISABLED site's rows hold no routes yet, so the duplicate is allowed")
            .isEqualTo(1);

        // Enabling the draft site would put the duplicate into the route table:
        // the enable edit must be refused and the site must stay disabled.
        navigateToApp("/admin/sites/" + draftId);
        waitForHydration();
        String snapshot = page.locator("input[name='cms__snapshot']").inputValue();
        var enableResponse = postForm("/admin/sites/" + draftId,
            "name=Draft+Route+Site&upstream_kind=hohenheim%3Aaddress"
            + "&enabled=false&enabled=true"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9092"
            + "&cms__snapshot=" + java.net.URLEncoder.encode(snapshot, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(enableResponse.statusCode()).isIn(200, 302, 303);
        Row after = Models.get(SiteModel.class).findById(draftId);
        assertThat((Boolean) after.get(SiteModel.ENABLED))
            .as("enabling must be refused while the duplicate route exists")
            .isFalse();
    }

    @Test
    @Order(99)
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
