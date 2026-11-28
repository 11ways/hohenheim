package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.SiteTls;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.orm.datasource.StoredDocumentValues;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.peer.PeerRecordModel;
import be.elevenways.zenit.server.security.SecureTokens;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The master's Apps list in the browser with linked servers, one answering and two silent: every server's apps in one
 * list under a Server column and a count line, the silent servers' receding with when they were last seen (the time
 * alone today, the day with it before) under the notice over the rows, and the Server filter offering every server,
 * this one first, uncounted, a silent one in the board's words.
 *
 * AIDEV-NOTE: it writes the list, the list with its Server filter open, a linked server's app page and a silent
 * server's, to build/apps-across-servers-screenshots:
 * the evidence a landing compares with board FO119's Apps screens. The linked servers' told states are the master's own
 * app's facts under other names, kept as the master's sync keeps them, so every cell is drawn from real copy.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppsAcrossServersBrowserTest extends HohenheimTestBase {

    /** Relative to the gradle test worker's working directory, the project root. */
    private static final Path OUT = Paths.get("build", "apps-across-servers-screenshots");

    @Test
    void everyServersAppsStandInOneListJourney() {
        page.setViewportSize(1440, 900);
        String nameBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Cluster.NAME);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, "Robbedoes");
        List<UUID> servers = new ArrayList<>();
        Row site = ownSite("robbedoes-blog");
        Row ownApp = ownWorkload("robbedoes-api");
        try {
            // 1. Kuifje answers and told two apps; Gaston stopped answering a moment ago and told one; Haddock last
            //    answered three days ago and told one.
            UUID kuifje = server("Kuifje", Now.instant(), null);
            UUID gaston = server("Gaston", Now.instant().minusSeconds(60), LinkFailure.UNREACHABLE);
            UUID haddock = server("Haddock", Now.instant().minus(Duration.ofDays(3)), LinkFailure.UNREACHABLE);
            servers.add(kuifje);
            servers.add(gaston);
            servers.add(haddock);
            AppFacts own = AppDirectory.operatorFacts().stream().filter(facts -> "robbedoes-blog".equals(facts.name()))
                .findFirst().orElseThrow();
            told(kuifje, own, 101, "kuifje-shop");
            told(kuifje, own, 102, "kuifje-wiki");
            told(gaston, own, 201, "gaston-forum");
            told(haddock, own, 301, "haddock-docs");
            AppFacts workload = new AppFacts(AppDirectory.Source.WORKLOAD, 103, "kuifje-api",
                InstanceModel.KIND.getValues().get("hohenheim:docker_container").getLabel(), own.address(), "kuifje",
                own.https(), own.health(), Now.instant().minus(Duration.ofHours(2)));
            keep(kuifje, workload);
            // The records the apps are made of, as the master keeps them: each app's page opens on them.
            heldWorkload(kuifje, 103, "kuifje-api");
            heldEstate(kuifje, 101, "kuifje-shop", "shop.kuifje.test");
            heldEstate(gaston, 201, "gaston-forum", "forum.gaston.test");

            // 2. One list: the master's app and both servers' apps, each under its server's name.
            navigateToApp("/admin/" + HohenheimSlugs.APPS);
            waitForHydration();
            Locator rows = page.locator("pl-table-body pl-table-row");
            for (String[] app : new String[][] {{"robbedoes-blog", "Robbedoes"}, {"kuifje-shop", "Kuifje"},
                    {"kuifje-wiki", "Kuifje"}, {"gaston-forum", "Gaston"}}) {
                Locator row = rows.filter(new Locator.FilterOptions().setHasText(app[0]));
                BoundedAssertions.assertThat(row).isVisible();
                assertThat(row.locator("pl-table-cell[data-column='server']").textContent().trim())
                    .as("step 2: %s under its server", app[0]).isEqualTo(app[1]);
            }

            // 3. The silent servers' apps recede with when their server was last seen, as a plain line: the time alone
            //    today, the day with it before; an answering server's do not.
            Locator silent = rows.filter(new Locator.FilterOptions().setHasText("gaston-forum"));
            assertThat(silent.getAttribute("inactive")).as("step 3: the silent server's row recedes").isNotNull();
            Locator seen = silent.locator("pl-table-cell[data-column='https']");
            assertThat(seen.textContent().trim()).as("step 3: seen today, the time alone")
                .matches("Last seen \\d{1,2}:\\d{2}( [AP]M)?");
            assertThat(seen.locator("pl-badge").count()).as("step 3: a plain line, no badge").isZero();
            assertThat(rows.filter(new Locator.FilterOptions().setHasText("haddock-docs"))
                .locator("pl-table-cell[data-column='https']").textContent().trim())
                .as("step 3: seen before today, the day with the time").startsWith("Last seen ")
                .doesNotMatch("Last seen \\d{1,2}:\\d{2}( [AP]M)?");
            assertThat(rows.filter(new Locator.FilterOptions().setHasText("kuifje-shop")).getAttribute("inactive"))
                .as("step 3: an answering server's row does not").isNull();

            // 4. The notice stands over the rows, not above the heading, and the count line says what the list holds.
            assertThat(page.locator("[data-cms-list-card-notice]").textContent())
                .as("step 4: the notice over the rows").containsPattern("Gaston does not answer since \\d{1,2}:\\d{2}")
                .contains("Haddock does not answer since");
            assertThat(page.locator("[data-cms-list-notice]").textContent().trim()).as("step 4: none above the heading")
                .isEmpty();
            assertThat(page.locator("[data-cms-list-tool-note]").textContent().trim()).as("step 4: the count line")
                .matches("\\d+ apps on 4 servers");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("apps-across-servers.png"))
                .setFullPage(true));

            // 5. The Server filter, after the others, offers every server uncounted, this one first, a silent one as
            //    the board words it.
            click("[data-cms-filter-toggle]");
            assertThat(page.locator("[data-cms-filter-bar] .cms-filter-field[data-filter-name]").evaluateAll(
                "fields => fields.map(field => field.dataset.filterName)")).as("step 5: Kind, Host, State, Server")
                .isEqualTo(List.of("type", "host", "state", "server"));
            Locator filter = page.locator("[data-cms-filter-bar] [data-filter-name='server'] pl-select");
            BoundedAssertions.assertThat(filter).isVisible();
            waitForReactiveIdle();
            filter.click();
            Locator options = page.getByRole(AriaRole.OPTION);
            BoundedAssertions.assertThat(options.filter(new Locator.FilterOptions()
                .setHasText("Gaston, does not answer"))).isVisible();
            assertThat(options.allTextContents().stream().map(String::trim).toList()).as("step 5: every server")
                .containsExactly("All servers", "Robbedoes", "Gaston, does not answer", "Haddock, does not answer",
                    "Kuifje");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("apps-server-filter.png"))
                .setFullPage(true));

            // 6. A linked server's workload opens the SAME page a workload of this server opens, under the key the
            //    master keeps it by, drawn from what its server told: its name, kind, host and server, the waiting
            //    tabs said in words, its power verbs (they run where it lives), and no other tab.
            String heldWorkloadPage = "/admin/" + HohenheimSlugs.INSTANCES + "/" + peerOf(kuifje) + "-103";
            navigateToApp(heldWorkloadPage + "/page/" + RecordOverview.SLUG);
            waitForHydration();
            String held = page.locator("body").textContent();
            assertThat(held).as("step 6: Kuifje's workload by its told facts").contains("kuifje-api")
                .contains("Kuifje").contains("kuifje").contains("Restart")
                .contains("Logs, console, files, stats, backups, volumes, databases and history wait");
            Object linked = page.locator("a[href*='" + heldWorkloadPage + "/page/']").evaluateAll(
                "links => [...new Set(links.map(link => link.getAttribute('href').split('/page/')[1]"
                    + ".split(/[?#]/)[0]))]");
            assertThat(linked).as("step 6: the page's tab links read").isInstanceOf(List.class);
            assertThat(((List<?>) linked).stream().map(String::valueOf).toList())
                .as("step 6: no tab beside the overview (a strip of one is not drawn)")
                .isSubsetOf(RecordOverview.SLUG);
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("held-app-page.png")).setFullPage(true));
            //    This server's own workload opens the same page, for the board's comparison of the two.
            navigateToApp("/admin/" + HohenheimSlugs.INSTANCES + "/" + ownApp.get(InstanceModel.ID) + "/page/"
                + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.locator("body").textContent()).as("step 6: this server's workload")
                .contains("robbedoes-api");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("local-app-page.png")).setFullPage(true));
            //    A linked server's website opens the page this server's website opens, and this server's own beside it.
            navigateToApp("/admin/" + HohenheimSlugs.SITES + "/" + peerOf(kuifje) + "-101/page/" + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.locator("body").textContent()).as("step 6: Kuifje's website").contains("kuifje-shop")
                .contains("Kuifje");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("held-website-page.png"))
                .setFullPage(true));
            //    Its Addresses tab lists the address Kuifje keeps of it, under no Server column, offering no add.
            navigateToApp("/admin/" + HohenheimSlugs.SITES + "/" + peerOf(kuifje) + "-101/page/"
                + SiteParts.DOMAINS_TAB);
            waitForHydration();
            Locator heldAddress = page.locator("pl-table-body pl-table-row")
                .filter(new Locator.FilterOptions().setHasText("shop.kuifje.test"));
            BoundedAssertions.assertThat(heldAddress).isVisible();
            assertThat(heldAddress.locator("pl-table-cell[data-column='hostname']").count())
                .as("step 6: the row draws its address cell").isEqualTo(1);
            assertThat(page.locator("pl-table-cell[data-column='server']").count()).as("step 6: no Server column")
                .isZero();
            assertThat(page.locator("a[href*='/" + HohenheimSlugs.DOMAINS + "/create']").count())
                .as("step 6: no add on Kuifje's site").isZero();
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("held-website-addresses.png"))
                .setFullPage(true));
            navigateToApp("/admin/" + HohenheimSlugs.SITES + "/" + site.get(SiteModel.ID) + "/page/"
                + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.locator("body").textContent()).as("step 6: this server's website").contains("robbedoes-blog");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("local-website-page.png"))
                .setFullPage(true));

            // 7. A silent server's app page says since when it does not answer and offers nothing to do.
            navigateToApp("/admin/" + HohenheimSlugs.SITES + "/" + peerOf(gaston) + "-201/page/"
                + RecordOverview.SLUG);
            waitForHydration();
            assertThat(page.locator("body").textContent()).as("step 7: the silent server's app page")
                .containsPattern("Gaston does not answer since \\d{1,2}:\\d{2}")
                .contains("Nothing can be changed until Gaston answers.");
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("held-app-silent.png"))
                .setFullPage(true));

            // 8. The Sites, Addresses and Certificates lists hold Kuifje's and Gaston's beside this server's, drawn by
            //    this server's cells from their records and told state, under their server's name; Gaston's recede
            //    with when it was last seen, under the list's notice in its own words; none draws a selection box.
            //    (Kuifje's kuifje-shop and Gaston's gaston-forum, kept in step 1.)
            String[][] lists = {{HohenheimSlugs.SITES, "name", "kuifje-shop", "gaston-forum", "Its sites are shown"},
                {HohenheimSlugs.DOMAINS, "hostname", "shop.kuifje.test", "forum.gaston.test",
                    "Its addresses are shown"},
                {HohenheimSlugs.CERTIFICATES, "nice_name", "kuifje-shop-cert", "gaston-forum-cert",
                    "Its certificates are shown"}};
            for (String[] list : lists) {
                navigateToApp("/admin/" + list[0]);
                waitForHydration();
                Locator listed = page.locator("pl-table-body pl-table-row");
                if (HohenheimSlugs.SITES.equals(list[0])) {
                    BoundedAssertions.assertThat(listed.filter(new Locator.FilterOptions()
                        .setHasText("robbedoes-blog"))).isVisible();
                }
                Locator answering = listed.filter(new Locator.FilterOptions().setHasText(list[2]));
                BoundedAssertions.assertThat(answering).isVisible();
                assertThat(answering.locator("pl-table-cell[data-column='server']").textContent().trim())
                    .as("step 8: %s under Kuifje", list[2]).isEqualTo("Kuifje");
                assertThat(answering.getAttribute("inactive")).as("step 8: %s does not recede", list[2]).isNull();
                assertThat(answering.locator("pl-table-select-row").count()).as("step 8: %s draws no box", list[2])
                    .isZero();
                Locator receding = listed.filter(new Locator.FilterOptions().setHasText(list[3]));
                assertThat(receding.getAttribute("inactive")).as("step 8: %s recedes", list[3]).isNotNull();
                assertThat(receding.textContent()).as("step 8: %s says when Gaston was last seen", list[3])
                    .contains("Last seen");
                assertThat(page.locator("[data-cms-list-card-notice]").textContent())
                    .as("step 8: the %s notice", list[0]).contains("Gaston does not answer since").contains(list[4]);
                page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve(list[0] + "-across-servers.png"))
                    .setFullPage(true));
            }

            // 9. Each list stays open while Kuifje tells of a new site, its address and its certificate: the new
            //    thing joins the open list by itself, no reload.
            String[][] told = {{HohenheimSlugs.SITES, "kuifje-news"}, {HohenheimSlugs.DOMAINS, "mail.kuifje.test"},
                {HohenheimSlugs.CERTIFICATES, "kuifje-docs-cert"}};
            String[][] estates = {{"kuifje-news", "news.kuifje.test"}, {"kuifje-mail", "mail.kuifje.test"},
                {"kuifje-docs", "docs.kuifje.test"}};
            for (int at = 0; at < told.length; at++) {
                navigateToApp("/admin/" + told[at][0]);
                waitForHydration();
                waitForReactiveIdle();
                Locator joined = page.locator("pl-table-body pl-table-row")
                    .filter(new Locator.FilterOptions().setHasText(told[at][1]));
                assertThat(joined.count()).as("step 9: %s is not listed before Kuifje tells it", told[at][1]).isZero();
                heldEstate(kuifje, 106 + at, estates[at][0], estates[at][1]);
                BoundedAssertions.assertThat(joined).isVisible();
                assertThat(joined.locator("pl-table-cell[data-column='server']").textContent().trim())
                    .as("step 9: %s joined the open %s list under Kuifje", told[at][1], told[at][0])
                    .isEqualTo("Kuifje");
            }
        } finally {
            Models.get(PeerRecordModel.class).find().where(PeerRecordModel.PEER.in(servers.stream()
                .map(String::valueOf).toList())).delete();
            Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.in(servers)).delete();
            Models.get(SiteModel.class).find().where(SiteModel.ID.eq(site.get(SiteModel.ID))).delete();
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(ownApp.get(InstanceModel.ID))).delete();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, nameBefore);
        }
    }

    /** @return a site of this server's own, made for the journey */
    private static Row ownSite(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + name));
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }

    /** @return a workload of this server's own, made for the journey */
    private static Row ownWorkload(String name) {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        instances.save(instance);
        return instance;
    }

    /**
     * Plants a confirmed server this one manages, last seen at {@code seen}, failing as {@code failing} since then.
     *
     * @return its id
     */
    private static UUID server(String name, Instant seen, LinkFailure failing) {
        UUID id = Uuids.v7();
        Row row = Models.get(LinkedServerModel.class).createEmptyRow();
        row.set(LinkedServerModel.ID, id);
        row.set(LinkedServerModel.NAME, name);
        row.set(LinkedServerModel.ROLE, LinkRole.MANAGES.token());
        row.set(LinkedServerModel.BASE_URL, "https://" + name.toLowerCase() + ".example.test");
        row.set(LinkedServerModel.LINK_KEY, SecureTokens.randomToken(32));
        row.set(LinkedServerModel.REMOTE_TOKEN, name.toLowerCase() + "-token");
        row.set(LinkedServerModel.BUILD, LinkedServers.ownVersion());
        row.set(LinkedServerModel.CONFIRMED_AT, seen);
        row.set(LinkedServerModel.LAST_SEEN_AT, seen);
        if (failing != null) {
            row.set(LinkedServerModel.LAST_FAILURE, failing.token());
            row.set(LinkedServerModel.FAILING_SINCE, seen.plus(Duration.ofMinutes(1)));
        }
        Models.get(LinkedServerModel.class).insert(row, Change.PLAIN);
        return id;
    }

    /** Keeps an app's state as that server told it, as the master's sync keeps it: this server's facts renamed. */
    private static void told(UUID server, AppFacts own, int id, String name) {
        keep(server, new AppFacts(own.source(), id, name, own.kind(), own.address(), own.host(), own.https(),
            own.health(), own.started()));
    }

    /** @return the key the master keeps a linked server's records under */
    private static String peerOf(UUID server) {
        return ClusterRecords.peerKey(Models.get(LinkedServerModel.class).findById(server));
    }

    /** Keeps the facts as the state that server told of one app, as the master's sync keeps it. */
    private static void keep(UUID server, AppFacts facts) {
        keep(server, AppStates.KIND, facts.key(), facts.name(), facts.document());
    }

    /** Keeps a record or state of one thing as that server told it, as the master's sync keeps it. */
    private static void keep(UUID server, Identifier kind, String key, String title, String document) {
        Row row = Models.get(PeerRecordModel.class).createEmptyRow();
        row.set(PeerRecordModel.ID, Uuids.v7());
        row.set(PeerRecordModel.PEER, peerOf(server));
        row.set(PeerRecordModel.MODEL, kind.toString());
        row.set(PeerRecordModel.RECORD_KEY, key);
        row.set(PeerRecordModel.TITLE, title);
        row.set(PeerRecordModel.DOCUMENT, document);
        row.set(PeerRecordModel.DIGEST, "planted");
        row.set(PeerRecordModel.FIRST_SEEN, Now.instant());
        row.set(PeerRecordModel.LAST_CHANGED, Now.instant());
        Models.get(PeerRecordModel.class).insert(row, Change.PLAIN);
    }

    /** Keeps one workload of a linked server as its sync keeps it: the portable document of a row never stored here. */
    private static void heldWorkload(UUID server, int id, String name) {
        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.ID, id);
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        keep(server, InstanceModel.MODEL_ID, String.valueOf(id), name, portable(instances, instance));
    }

    /**
     * Keeps one website of a linked server, its one address and the certificate covering it, as that server's sync
     * keeps them: the records as portable documents of rows never stored here, the state as that server tells it.
     */
    private static void heldEstate(UUID server, int id, String name, String hostname) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.ID, id);
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/srv/" + name));
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.CREATED_AT, Now.instant());
        keep(server, SiteModel.MODEL_ID, String.valueOf(id), name, portable(sites, site));
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.ID, id + 400);
        domain.set(SiteDomainModel.SITE_ID, id);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, true);
        keep(server, SiteDomainModel.MODEL_ID, String.valueOf(id + 400), hostname, portable(domains, domain));
        RecordHealth working = new RecordHealth(HealthTone.OK, AppParts.stateLabel(HealthTone.OK.token()), null,
            List.of(), null);
        keep(server, SiteStates.SITE_KIND, String.valueOf(id), name, Zenit.DRY.toJson(Map.of(
            "tls", ToldFacts.line(StateLineCell.of(SiteTls.WORKS, null)), "health", ToldFacts.health(working))));
        keep(server, SiteStates.ADDRESS_KIND, String.valueOf(id + 400), hostname, Zenit.DRY.toJson(Map.of(
            "reach", ToldFacts.line(StateLineCell.of(AddressReach.POINTS_HERE, null)),
            "https", ToldFacts.line(StateLineCell.of(CertCoverage.ACTIVE, null)))));
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row cert = certificates.createEmptyRow();
        cert.set(CertificateModel.ID, id + 600);
        cert.set(CertificateModel.NICE_NAME, name + "-cert");
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, hostname);
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.AUTO_RENEW, true);
        cert.set(CertificateModel.KEY_HOLDER, CertificateModel.KEY_HOLDER_HOHENHEIM);
        cert.set(CertificateModel.EXPIRES_ON, Now.instant().plus(Duration.ofDays(60)));
        Map<String, Object> told = new LinkedHashMap<>();
        Map<String, Object> values = StoredDocumentValues.portableDocument(certificates, cert,
            certificates.getResolvedDatasource());
        for (String column : CertificateParts.toldColumns()) {
            told.put(column, values.get(column));
        }
        told.put("state", ToldFacts.line(CertificateParts.stateCell(cert, true)));
        told.put("health", ToldFacts.health(working));
        keep(server, CertificateStates.KIND, String.valueOf(id + 600), name + "-cert", Zenit.DRY.toJson(told));
    }

    /** @return a row's portable document, as a linked server's sync sends its shared record */
    private static String portable(Model model, Row row) {
        return Zenit.DRY.toJson(StoredDocumentValues.portableDocument(model, row, model.getResolvedDatasource()));
    }
}
