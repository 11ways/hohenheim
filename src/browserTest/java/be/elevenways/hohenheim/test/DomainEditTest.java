package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain CRUD through the (nav-hidden) zenit-cms domain resource: relation
 * pick to the site, header maps, uniqueness validation, and the site's
 * Domains tab.
 *
 * AIDEV-NOTE: every test builds its OWN site and hostname through {@link #fixture}. This
 * class used to be one 20-step @Order chain passing static ids along, so running one method
 * alone NPE'd and one failure cascaded through every later test. Route identity is GLOBAL
 * across enabled sites, so each test's hostname carries its own tag and cannot collide with
 * another test (or class) in the shared-server JVM.
 */
class DomainEditTest extends HohenheimTestBase {

    /** The copy of the refusals this class provokes, as the admin reads them rerendered. */
    private static final String HOSTNAME_REQUIRED = "Hostname is required";
    private static final String HOSTNAME_TAKEN = "That hostname is already configured for this site";
    private static final String ROUTE_TAKEN = "That hostname and path are already routed on this site";
    private static final String ROUTE_TAKEN_OTHER_SITE = "This route is already claimed by site";
    private static final String ENABLE_ROUTE_CONFLICT = "Cannot enable this site";

    /** One test's own site and its first domain row. */
    private record Fixture(int siteId, String siteName, String hostname, int domainId) {
    }

    @BeforeAll
    static void discoverListenAddresses() {
        // The listen_on select validates against discovered addresses; the boot
        // task that populates them does not run in the test JVM.
        UpdateSystemIpAddresses.discover();
    }

    /** A site of its own plus one exact domain row; {@code domainExtra} adds form fields. */
    private Fixture fixture(String tag, String domainExtra) throws Exception {
        String siteName = siteNameFor(tag);
        int siteId = createSite(siteName, "");
        String hostname = hostFor(tag);
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + hostname + "&match_type=exact" + domainExtra);
        assertThat(response.statusCode()).as("fixture: the domain %s is accepted", hostname)
            .isIn(200, 302, 303);
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname))
            .where(SiteDomainModel.SITE_ID.eq(siteId))
            .first();
        assertThat(domain).as("fixture: the domain %s is stored", hostname).isNotNull();
        return new Fixture(siteId, siteName, hostname, domain.get(SiteDomainModel.ID));
    }

    private int createSite(String name, String extra) throws Exception {
        var response = adminPostForm("/admin/sites/new",
            "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "&upstream_kind=hohenheim%3Aaddress"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9090" + extra);
        assertThat(response.statusCode()).as("fixture: the site %s is accepted", name)
            .isIn(200, 302, 303);
        Row site = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
        assertThat(site).as("fixture: the site %s is stored", name).isNotNull();
        return site.get(SiteModel.ID);
    }

    private static String siteNameFor(String tag) {
        return "Domain Edit " + tag;
    }

    private static String hostFor(String tag) {
        return tag.toLowerCase(Locale.ROOT) + ".domain-edit.example.com";
    }

    /** A refused save rerenders the form (never a redirect) carrying the violation's copy. */
    private static void assertRefused(HttpResponse<String> response, String sentence, String what) {
        assertThat(response.statusCode())
            .as(what + ": the refusal rerenders the form instead of redirecting")
            .isEqualTo(200);
        assertThat(response.body())
            .as(what + ": and names the violation")
            .contains(sentence);
    }

    @Test
    void createSiteAndDomain() throws Exception {
        // 1. A site of its own.
        int siteId = createSite(siteNameFor("create"), "");

        // 2. The new-domain form opens with its defaults.
        navigateToApp("/admin/domains/new?site_id=" + siteId);
        waitForHydration();
        assertThat(page.locator("pl-select[name='match_type'] .pl-select-value")
            .textContent().trim()).as("step 2: match type defaults to exact").isEqualTo("Exact hostname");
        assertThat(page.locator("pl-switch[name='force_ssl']").getAttribute("checked"))
            .as("step 2: force SSL defaults on").isNotNull();

        // 3. The created domain belongs to that site.
        var domainResponse = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + hostFor("create") + "&match_type=exact");
        assertThat(domainResponse.statusCode()).as("step 3: the domain create is accepted")
            .isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostFor("create"))).first();
        assertThat(domain).as("step 3: the domain is stored").isNotNull();
        assertThat((Integer) domain.get(SiteDomainModel.SITE_ID)).as("step 3: on the picked site")
            .isEqualTo(siteId);
    }

    @Test
    void domainAppearsOnSiteDomainsTab() throws Exception {
        Fixture f = fixture("tab", "");
        int siteId = f.siteId();
        int domainId = f.domainId();
        navigateToApp("/admin/sites/" + siteId + "/page/domains");
        waitForHydration();

        assertThat(page.locator("body").textContent()).as("the tab lists the hostname")
            .contains(f.hostname());
        // The hostname itself opens the form; the actions cell offers the same edit
        // explicitly plus the remove this tab used to have no way to reach.
        assertThat(page.locator("a[href^='/admin/domains/" + domainId + "?']").count())
            .isGreaterThanOrEqualTo(1);
        var actions = page.locator(".hh-domain-row-actions");
        assertThat(actions.count()).as("the row carries an actions cell").isEqualTo(1);
        assertThat(actions.locator("a.hh-domain-edit[href^='/admin/domains/" + domainId + "?']").count())
            .as("with an explicit edit link, bound back to this tab").isEqualTo(1);
        assertThat(actions.locator("a.hh-domain-edit").first().getAttribute("href"))
            .as("the edit link carries the tab as its return target").contains("_return=");
        assertThat(actions.locator("form[action*='/admin/domains/" + domainId + "/delete']").count())
            .as("and a remove form posting to the delete route").isEqualTo(1);
        assertThat(actions.locator("form pl-button[type='submit']").count())
            .as("removal is a real submit, never a bare link").isEqualTo(1);
    }

    /**
     * The domains catalog is REACHABLE: the Sites list names it in its related-pages menu,
     * and its own site column is labelled for the value it shows (the name, not the id).
     */
    @Test
    void domainsCatalogIsNamedByTheSitesListAndLabelsItsSiteColumn() throws Exception {
        String sites = adminGet("/admin/sites").body();
        assertThat(sites)
            .as("the nav-hidden domains peer is named by the Sites list")
            .contains("href=\"/admin/domains\"");

        String domains = adminGet("/admin/domains").body();
        assertThat(domains)
            .as("the relation column is headed by what it shows")
            .doesNotContain("Site id");
    }

    @Test
    void editFormRendersTheDomain() throws Exception {
        Fixture f = fixture("form", "");
        navigateToApp("/admin/domains/" + f.domainId());
        waitForHydration();

        assertThat(page.content()).as("the form carries the hostname").contains(f.hostname());
        assertThat(page.locator("form").count()).as("the record page renders a form").isGreaterThan(0);

        // The child record page breadcrumbs back to the owning site's Domains
        // tab, and record titles render as literal text -- never as microcopy
        // keys (user data must not enter the translation pipeline).
        var crumbs = page.locator(".cms-breadcrumbs");
        assertThat(crumbs.count()).as("one breadcrumb trail").isEqualTo(1);
        assertThat(crumbs.textContent()).as("naming the owning site").contains(f.siteName());
        assertThat(crumbs.textContent()).as("and the domain itself").contains(f.hostname());
        assertThat(page.locator(".cms-breadcrumbs a[href='/admin/sites/" + f.siteId()
            + "/page/domains']").count()).as("linking back to the site's Domains tab").isEqualTo(1);
        assertThat(page.locator(".cms-breadcrumbs zn-microcopy[key='" + f.siteName() + "']").count())
            .as("the site name is literal text, never a microcopy key").isEqualTo(0);
    }

    @Test
    void updateDomainSettingsIncludingHeaderMaps() throws Exception {
        Fixture f = fixture("update", "");
        var response = adminPostForm("/admin/domains/" + f.domainId(),
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=wildcard"
            + "&force_ssl=true&hsts_enabled=true"
            + "&path=%2Fapp&strip_path=true&listen_on=127.0.0.1"
            + "&custom_headers.0.key=X-Injected&custom_headers.0.value=yes"
            + "&response_headers.0.key=X-Strip-Me&response_headers.0.value=");
        assertThat(response.statusCode()).as("the update is accepted").isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).findById(f.domainId());
        assertThat((String) domain.get(SiteDomainModel.MATCH_TYPE)).as("match type").isEqualTo("wildcard");
        assertThat((Boolean) domain.get(SiteDomainModel.FORCE_SSL)).as("force SSL").isEqualTo(true);
        assertThat((Boolean) domain.get(SiteDomainModel.HSTS_ENABLED)).as("HSTS").isEqualTo(true);
        assertThat((String) domain.get(SiteDomainModel.PATH)).as("path").isEqualTo("/app");
        assertThat((String) domain.get(SiteDomainModel.LISTEN_ON)).as("listen_on").isEqualTo("127.0.0.1");

        Map<String, String> headers = domain.get(SiteDomainModel.CUSTOM_HEADERS);
        assertThat(headers).as("the request header map").containsEntry("X-Injected", "yes");
        Map<String, String> responseHeaders = domain.get(SiteDomainModel.RESPONSE_HEADERS);
        assertThat(responseHeaders).as("the response header map").containsEntry("X-Strip-Me", "");
    }

    @Test
    void blankHostnameIsRejected() throws Exception {
        Fixture f = fixture("blank", "");
        var response = adminPostForm("/admin/domains/" + f.domainId(),
            "site_id=" + f.siteId() + "&hostname=&match_type=exact");
        assertRefused(response, HOSTNAME_REQUIRED, "a blank hostname");

        Row domain = Models.get(SiteDomainModel.class).findById(f.domainId());
        assertThat((String) domain.get(SiteDomainModel.HOSTNAME))
            .as("a blank hostname must not overwrite the stored one")
            .isEqualTo(f.hostname());
    }

    @Test
    void duplicateHostnameAndPathOnSameSiteIsRejected() throws Exception {
        Fixture f = fixture("dup", "&path=%2Fapp");
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact&path=%2Fapp");
        assertRefused(response, ROUTE_TAKEN, "the same hostname and path");

        assertThat(domainsNamed(f.hostname()))
            .as("the same hostname on the same path is one route, so the second row is refused")
            .isEqualTo(1);
    }

    /**
     * Uniqueness must compare CANONICAL paths: "app", "/app" and "/app/" are one
     * route to the dispatcher, so the editor has to refuse all three as duplicates.
     */
    @Test
    void duplicatePathIsRejectedRegardlessOfSlashSpelling() throws Exception {
        Fixture f = fixture("slash", "&path=%2Fapp");
        for (String spelling : new String[] {"app", "%2Fapp%2F", "+%2Fapp+"}) {
            var response = adminPostForm("/admin/domains/new",
                "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact"
                + "&path=" + spelling);
            assertRefused(response, ROUTE_TAKEN, "path spelling '" + spelling + "'");

            assertThat(domainsNamed(f.hostname()))
                .as("path spelling '" + spelling + "' canonicalizes to /app and must be refused")
                .isEqualTo(1);
        }
    }

    /**
     * The dispatcher routes host+path pairs, so one hostname may legitimately fan out
     * over several paths on the same site (how the NetBird gRPC/API split is configured).
     */
    @Test
    void sameHostnameOnADifferentPathIsAccepted() throws Exception {
        Fixture f = fixture("paths", "&path=%2Fapp");
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact"
            + "&path=%2Fmanagement.ProxyService");
        assertThat(response.statusCode()).as("the second path is accepted").isIn(200, 302, 303);

        assertThat(domainsNamed(f.hostname()))
            .as("a different path is a different route and must be accepted")
            .isEqualTo(2);

        Row added = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(f.hostname()))
            .where(SiteDomainModel.PATH.eq("/management.ProxyService"))
            .first();
        assertThat(added).as("the second path row is stored").isNotNull();
    }

    /** A catch-all row alongside a path row is also a distinct route. */
    @Test
    void catchAllAlongsidePathRowsIsAccepted() throws Exception {
        Fixture f = fixture("catchall", "&path=%2Fapp");
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact&path=");
        assertThat(response.statusCode()).as("the catch-all is accepted").isIn(200, 302, 303);

        assertThat(domainsNamed(f.hostname())).as("the catch-all is a route of its own").isEqualTo(2);

        // ...but only once: blank and "/" are the same catch-all route.
        var slash = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact&path=%2F");
        assertRefused(slash, HOSTNAME_TAKEN, "a second catch-all spelled \"/\"");
        assertThat(domainsNamed(f.hostname()))
            .as("blank and \"/\" are the same catch-all route")
            .isEqualTo(2);
    }

    /** Editing a row must not treat the row's own stored path as a conflict. */
    @Test
    void updatingARowKeepingItsPathIsAllowed() throws Exception {
        Fixture f = fixture("keep", "&path=%2Fapp");
        var response = adminPostForm("/admin/domains/" + f.domainId(),
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=wildcard"
            + "&path=%2Fapp&force_ssl=true");
        assertThat(response.statusCode()).as("the self-update is accepted").isIn(200, 302, 303);

        Row domain = Models.get(SiteDomainModel.class).findById(f.domainId());
        assertThat((String) domain.get(SiteDomainModel.PATH)).as("the path is kept").isEqualTo("/app");
        assertThat((String) domain.get(SiteDomainModel.MATCH_TYPE))
            .as("and the update itself was written").isEqualTo("wildcard");
    }

    /** Moving a row onto a sibling's path is still a conflict. */
    @Test
    void updatingARowOntoASiblingPathIsRejected() throws Exception {
        Fixture f = fixture("sibling", "&path=%2Fapp");
        var sibling = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact"
            + "&path=%2Fmanagement.ProxyService");
        assertThat(sibling.statusCode()).as("the sibling path row is accepted").isIn(200, 302, 303);
        assertThat(domainsNamed(f.hostname())).as("both rows exist").isEqualTo(2);

        var response = adminPostForm("/admin/domains/" + f.domainId(),
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact"
            + "&path=%2Fmanagement.ProxyService");
        assertRefused(response, ROUTE_TAKEN, "moving onto the sibling's path");

        Row domain = Models.get(SiteDomainModel.class).findById(f.domainId());
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
    void caseVariantHostnameIsRejected() throws Exception {
        Fixture f = fixture("case", "&path=%2Fapp");
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=Case.Domain-Edit.EXAMPLE.com"
            + "&match_type=exact&path=%2Fapp");
        assertRefused(response, ROUTE_TAKEN, "a case-variant hostname");

        assertThat(domainsNamed(f.hostname()))
            .as("a case-variant hostname collapses to the same route and must be refused")
            .isEqualTo(1);
    }

    /**
     * normalizeRoutePath must be a fixpoint: "//" canonicalizes to the catch-all
     * (null) exactly like "/" -- it used to canonicalize to "/", which the editor
     * accepted as a distinct row and the route build then collapsed first-wins.
     */
    @Test
    void doubleSlashIsTheSameCatchAll() throws Exception {
        Fixture f = fixture("slashes", "");
        var response = adminPostForm("/admin/domains/new",
            "site_id=" + f.siteId() + "&hostname=" + f.hostname() + "&match_type=exact&path=%2F%2F");
        assertRefused(response, HOSTNAME_TAKEN, "a catch-all spelled \"//\"");

        assertThat(domainsNamed(f.hostname()))
            .as("\"//\" is the same catch-all route as blank and \"/\"")
            .isEqualTo(1);
    }

    /**
     * Listener restrictions are part of route identity: two rows sharing host+path
     * are distinct only while their listener sets cannot overlap.
     */
    @Test
    void listenAddressesDecideRouteIdentity() throws Exception {
        int siteId = createSite(siteNameFor("listen"), "");
        String host = hostFor("listen");

        var first = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=127.0.0.1");
        assertThat(first.statusCode()).as("step 1: a restricted row is accepted").isIn(200, 302, 303);
        assertThat(domainsNamed(host)).as("step 1: and stored").isEqualTo(1);

        // ::1 always exists in the discovered set (the listen_on select validates
        // against it), so the disjoint case does not depend on this machine's NICs.
        var second = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=%3A%3A1");
        assertThat(second.statusCode()).as("step 2: a disjoint row is accepted").isIn(200, 302, 303);
        assertThat(domainsNamed(host))
            .as("a disjoint listen_on set is a distinct route")
            .isEqualTo(2);

        // Same address as the first row: one route, so the row must be refused.
        var overlapping = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact"
            + "&path=%2Fapp&listen_on=127.0.0.1");
        assertRefused(overlapping, ROUTE_TAKEN, "step 3: an overlapping listen_on set");
        assertThat(domainsNamed(host))
            .as("an overlapping listen_on set is the same route")
            .isEqualTo(2);

        // An UNRESTRICTED row listens everywhere, so it overlaps both restricted ones.
        var unrestricted = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Fapp");
        assertRefused(unrestricted, ROUTE_TAKEN, "step 4: an unrestricted listener set");
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
    void trailingWhitespacePathCanonicalizesToTheSameRoute() throws Exception {
        int siteId = createSite(siteNameFor("fixpoint"), "");
        String host = hostFor("fixpoint");
        var first = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Ffx%20%2F");
        assertThat(first.statusCode()).as("the residue-bearing path is accepted").isIn(200, 302, 303);
        Row stored = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(host)).first();
        assertThat(stored).as("the row is stored").isNotNull();
        assertThat((String) stored.get(SiteDomainModel.PATH))
            .as("the canonical path is stored, residue like \"/fx \" never routes")
            .isEqualTo("/fx");

        var again = adminPostForm("/admin/domains/new",
            "site_id=" + siteId + "&hostname=" + host + "&match_type=exact&path=%2Ffx");
        assertRefused(again, ROUTE_TAKEN, "the canonical spelling of the same path");
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
    void sameRouteOnAnotherEnabledSiteIsRejected() throws Exception {
        Fixture f = fixture("cross", "");
        int second = createSite(siteNameFor("cross second"), "");

        var response = adminPostForm("/admin/domains/new",
            "site_id=" + second + "&hostname=" + f.hostname() + "&match_type=exact");
        assertRefused(response, ROUTE_TAKEN_OTHER_SITE, "the same route on another enabled site");
        assertThat(response.body())
            .as("the operator is told which site holds it")
            .contains(f.siteName());
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(second)).count())
            .as("the same host+path on another enabled site is one route and must be refused")
            .isEqualTo(0);
    }

    @Test
    void disabledSiteRowsAreExemptUntilEnabling() throws Exception {
        // 1. A live site holds the route.
        Fixture f = fixture("draft", "");

        // 2. A DISABLED site may stage the same route.
        String draftName = siteNameFor("draft staging");
        Integer draftId = createSite(draftName, "&enabled=false");
        Row draft = Models.get(SiteModel.class).findById(draftId);
        assertThat((Boolean) draft.get(SiteModel.ENABLED)).as("step 2: the draft is disabled").isFalse();

        var domainResponse = adminPostForm("/admin/domains/new",
            "site_id=" + draftId + "&hostname=" + f.hostname() + "&match_type=exact");
        assertThat(domainResponse.statusCode()).as("step 2: the staged duplicate is accepted")
            .isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(draftId)).count())
            .as("a DISABLED site's rows hold no routes yet, so the duplicate is allowed")
            .isEqualTo(1);

        // 3. Enabling the draft site would put the duplicate into the route table:
        //    the enable edit must be refused and the site must stay disabled.
        navigateToApp("/admin/sites/" + draftId);
        waitForHydration();
        String snapshot = page.locator("input[name='cms__snapshot']").inputValue();
        var enableResponse = adminPostForm("/admin/sites/" + draftId,
            "name=" + URLEncoder.encode(draftName, StandardCharsets.UTF_8)
            + "&upstream_kind=hohenheim%3Aaddress"
            + "&enabled=false&enabled=true"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=9090"
            + "&cms__snapshot=" + URLEncoder.encode(snapshot, StandardCharsets.UTF_8));
        assertThat(enableResponse.statusCode()).as("step 3: the enable edit answers")
            .isIn(200, 302, 303);
        assertThat(enableResponse.body())
            .as("step 3: the refusal names the conflicting route")
            .contains(ENABLE_ROUTE_CONFLICT);
        Row after = Models.get(SiteModel.class).findById(draftId);
        assertThat((Boolean) after.get(SiteModel.ENABLED))
            .as("enabling must be refused while the duplicate route exists")
            .isFalse();
    }

    /** The tab's own remove control detaches the hostname and returns to the tab. */
    @Test
    void theDomainsTabRemoveControlDetachesTheHostname() throws Exception {
        Fixture f = fixture("remove", "");

        navigateToApp("/admin/sites/" + f.siteId() + "/page/domains");
        waitForHydration();
        // The POST goes to the target the page RENDERED, so the assertion covers the
        // affordance and the route it points at, not a hand-written URL.
        String action = page.locator("form[action*='/admin/domains/" + f.domainId() + "/delete']")
            .first().getAttribute("action");
        assertThat(action).as("the remove form returns to this tab")
            .contains("_return");

        var removed = adminPostForm(action, confirmed(""));
        assertThat(removed.statusCode()).as("the removal is accepted").isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).findById(f.domainId()))
            .as("the hostname is detached from the site")
            .isNull();
    }

    @Test
    void deleteRemovesTheDomain() throws Exception {
        Fixture f = fixture("delete", "");
        var response = adminPostForm("/admin/domains/" + f.domainId() + "/delete", confirmed(""));
        assertThat(response.statusCode()).as("the delete is accepted").isIn(200, 302, 303);
        assertThat(Models.get(SiteDomainModel.class).findById(f.domainId()))
            .as("the row is gone").isNull();
    }

    private static long domainsNamed(String hostname) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname))
            .count();
    }
}
