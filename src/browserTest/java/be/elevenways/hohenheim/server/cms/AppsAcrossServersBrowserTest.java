package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The master's Apps list in the browser with two linked servers, one answering and one silent: every server's apps
 * in one list under a Server column, the silent server's receding with when it was last seen, and the Server filter
 * offering every server, the silent one in the board's words.
 *
 * AIDEV-NOTE: it writes the list with the Server filter open to build/apps-across-servers-screenshots, the evidence a
 * landing compares with board FO119's Apps screen. The linked servers' told states are the master's own app's facts
 * under other names, kept as the master's sync keeps them, so every cell is drawn from real copy.
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
        try {
            // 1. Kuifje answers and told two apps; Gaston last answered two hours ago and told one.
            UUID kuifje = server("Kuifje", Now.instant(), null);
            UUID gaston = server("Gaston", Now.instant().minus(Duration.ofHours(2)), LinkFailure.UNREACHABLE);
            servers.add(kuifje);
            servers.add(gaston);
            AppFacts own = AppDirectory.operatorFacts().stream().filter(facts -> "robbedoes-blog".equals(facts.name()))
                .findFirst().orElseThrow();
            told(kuifje, own, 101, "kuifje-shop");
            told(kuifje, own, 102, "kuifje-wiki");
            told(gaston, own, 201, "gaston-forum");

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

            // 3. Gaston's app recedes with when Gaston was last seen; Kuifje's does not.
            Locator silent = rows.filter(new Locator.FilterOptions().setHasText("gaston-forum"));
            assertThat(silent.getAttribute("inactive")).as("step 3: the silent server's row recedes").isNotNull();
            assertThat(silent.textContent()).as("step 3: when it was last seen").contains("Last seen");
            assertThat(rows.filter(new Locator.FilterOptions().setHasText("kuifje-shop")).getAttribute("inactive"))
                .as("step 3: an answering server's row does not").isNull();

            // 4. The Server filter offers every server, the silent one as the board words it.
            click("[data-cms-filter-toggle]");
            Locator filter = page.locator("[data-cms-filter-bar] [data-filter-name='server'] pl-select");
            BoundedAssertions.assertThat(filter).isVisible();
            waitForReactiveIdle();
            filter.click();
            Locator options = page.getByRole(AriaRole.OPTION);
            BoundedAssertions.assertThat(options.filter(new Locator.FilterOptions()
                .setHasText("Gaston, does not answer"))).isVisible();
            assertThat(options.allTextContents()).as("step 4: every server").anyMatch(text -> text.contains("Kuifje"))
                .anyMatch(text -> text.contains("Robbedoes"));
            page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve("apps-server-filter.png"))
                .setFullPage(true));
        } finally {
            Models.get(PeerRecordModel.class).find().where(PeerRecordModel.PEER.in(servers.stream()
                .map(String::valueOf).toList())).delete();
            Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.in(servers)).delete();
            Models.get(SiteModel.class).find().where(SiteModel.ID.eq(site.get(SiteModel.ID))).delete();
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
        AppFacts facts = new AppFacts(own.source(), id, name, own.kind(), own.address(), own.host(), own.https(),
            own.health());
        Row row = Models.get(PeerRecordModel.class).createEmptyRow();
        row.set(PeerRecordModel.ID, Uuids.v7());
        row.set(PeerRecordModel.PEER, ClusterRecords.peerKey(Models.get(LinkedServerModel.class).findById(server)));
        row.set(PeerRecordModel.MODEL, AppStates.KIND.toString());
        row.set(PeerRecordModel.RECORD_KEY, facts.key());
        row.set(PeerRecordModel.TITLE, name);
        row.set(PeerRecordModel.DOCUMENT, facts.document());
        row.set(PeerRecordModel.DIGEST, "planted");
        row.set(PeerRecordModel.FIRST_SEEN, Now.instant());
        row.set(PeerRecordModel.LAST_CHANGED, Now.instant());
        Models.get(PeerRecordModel.class).insert(row, Change.PLAIN);
    }
}
