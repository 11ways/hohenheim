package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.LinkedServerCodeModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.MachineName;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.peer.PeerRecords;
import be.elevenways.zenit.server.peer.PeerWire;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedRowsPage;
import be.elevenways.zenit.server.security.SecureTokens;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Servers page in the browser: the Servers cluster with its Hosts tab, this server as the first row, the Add a
 * Hohenheim server form, the join code shown once in the card under the list, and this server named after its machine
 * while its name setting is empty.
 *
 * AIDEV-NOTE: it writes a screenshot of the page, the add form and the join code to build/linked-servers-screenshots,
 * the evidence a landing compares with the board screens (FO119: Servers, Server-Add, Server-Join-Code), and of the
 * list with each server's counts, a managed server's page and its remembered names (Servers, Server-Detail), and of
 * the dashboard's item for a restored server (Attention).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LinkedServersBrowserTest extends HohenheimTestBase {

    /** Relative to the gradle test worker's working directory, the project root. */
    private static final Path OUT = Paths.get("build", "linked-servers-screenshots");
    private static String nameBefore;

    @BeforeAll
    static void nameThisServer() {
        nameBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Cluster.NAME);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, "Robbedoes");
    }

    @AfterAll
    static void restoreName() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, nameBefore);
        Models.get(LinkedServerCodeModel.class).find().delete();
    }

    @Test
    void theServersPageAddsAServerAndShowsAJoinCodeOnceJourney() {
        page.setViewportSize(1440, 900);
        // 1. The sidebar's Servers lands on the linked servers, under the cluster's heading, with the Hosts tab.
        navigateToApp("/admin/" + HohenheimSlugs.Cluster.CLUSTER);
        waitForHydration();
        page.waitForCondition(() -> page.url().endsWith("/admin/" + HohenheimSlugs.LINKED_SERVERS));
        assertThat(page.locator("h1").first().textContent()).as("step 1: the cluster heads the page")
            .contains("Servers");
        String tabs = page.locator("[data-cms-cluster-tabs]").first().textContent();
        assertThat(tabs).as("step 1: the Servers and Hosts tabs").contains("Servers").contains("Hosts");

        // 2. This server is the first row: its name, "This server" in muted text under it (never a badge), reachable,
        //    under the column head "Server"; the list repeats no sentence of the page's own; an unmanaged server
        //    says so.
        String content = page.content();
        assertThat(content).as("step 2: this server's row").contains("Robbedoes").contains("This server")
            .contains("Reachable");
        assertThat(page.locator("[data-cms-second-line]").first().textContent()).as("step 2: the muted line")
            .contains("This server");
        assertThat(page.locator("pl-badge").filter(new Locator.FilterOptions().setHasText("This server")).count())
            .as("step 2: no badge says it").isZero();
        assertThat(page.locator("pl-table-head[data-column='name']").textContent())
            .as("step 2: the first column's head").contains("Server").doesNotContain("Name");
        assertThat(content).as("step 2: the page's sub line once, no list lead repeating it")
            .doesNotContain("The Hohenheim servers that work together.");
        assertThat(content).as("step 2: the card says nobody manages this server")
            .contains("No other Hohenheim manages this server.");
        shoot("servers");

        // 3. Add a Hohenheim server opens its form: its title, no sentence under it, then name, address and the join
        //    code with where to get it.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add a Hohenheim server")).first().click();
        // An operation asking input opens plumage's alert dialog (as zenit-cms ActionInputBrowserTest finds it).
        Locator form = page.locator(".pl-alertdialog-modal[data-open]");
        BoundedAssertions.assertThat(form).isVisible();
        // The dialog opens before its form arrives ("Loading the form..."): read the fields once they stand.
        BoundedAssertions.assertThat(form).containsText("It is valid for 10 minutes.");
        String fields = form.textContent();
        assertThat(fields).as("step 3: the three fields").contains("Name").contains("Address").contains("Join code");
        assertThat(fields).as("step 3: where the code comes from").contains("It is valid for 10 minutes.");
        assertThat(form.locator("pl-alert-dialog-description").count()).as("step 3: no sentence under the title")
            .isZero();
        // The picture waits for the dialog's and its backdrop's opening transitions to end: mid-fade, the page behind
        // shines through the dialog.
        waitForPageFunction("() => [...document.querySelectorAll('.pl-alertdialog-modal[data-open],"
            + " .pl-alertdialog-overlay')].every(e => e.getAnimations().length === 0)");
        shoot("server-add");
        form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel")).click();
        BoundedAssertions.assertThat(form).isHidden();

        // 4. Let another Hohenheim manage this server, a button beside Add, shows the code in the card under the
        //    list, grouped in fours with Copy and until when it is valid; a reload shows it no more, the card then
        //    says one waits.
        assertThat(page.locator("[data-cms-header-overflow]").count())
            .as("step 4: no More menu holds a header action").isZero();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
            .setName("Let another Hohenheim manage this server")).click();
        Locator shown = page.locator("[data-hh-join-code]");
        BoundedAssertions.assertThat(shown).isVisible();
        assertThat(shown.textContent()).as("step 4: the code, once, with until when it is valid")
            .containsPattern("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}").contains("Shown once, valid until");
        assertThat(shown.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Copy")).count())
            .as("step 4: the code can be copied").isPositive();
        shown.scrollIntoViewIfNeeded();
        shoot("server-join-code");
        navigateToApp("/admin/" + HohenheimSlugs.LINKED_SERVERS);
        waitForHydration();
        assertThat(page.locator("[data-hh-join-code]").count()).as("step 4: shown once only").isZero();
        assertThat(page.content()).as("step 4: the card says a code waits").contains("A join code is waiting");

        // 5. With its name setting empty this server is named after its machine.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, null);
        navigateToApp("/admin/" + HohenheimSlugs.LINKED_SERVERS);
        waitForHydration();
        assertThat(page.content()).as("step 5: the machine's name, the setting's no more")
            .contains(MachineName.firstLabel()).doesNotContain("Robbedoes");
    }

    /**
     * A server this one manages: the Servers list shows each server's own Apps and Sites counts, its row opens its
     * page with what this server remembers of it, kind by kind in the reader's words, a kind opens the remembered codes
     * and names a page at a time, and once it was restored the dashboard says so.
     */
    @Test
    void aManagedServerShowsItsCountsAndWhatThisServerRemembersJourney() {
        page.setViewportSize(1440, 900);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, "Robbedoes");
        Row kuifje = Models.get(LinkedServerModel.class).createEmptyRow();
        kuifje.set(LinkedServerModel.ID, Uuids.v7());
        kuifje.set(LinkedServerModel.NAME, "Kuifje");
        kuifje.set(LinkedServerModel.ROLE, LinkRole.MANAGES.token());
        kuifje.set(LinkedServerModel.BASE_URL, "https://kuifje.example.test");
        kuifje.set(LinkedServerModel.LINK_KEY, SecureTokens.randomToken(32));
        kuifje.set(LinkedServerModel.REMOTE_TOKEN, "kuifje-token");
        kuifje.set(LinkedServerModel.BUILD, LinkedServers.ownVersion());
        kuifje.set(LinkedServerModel.LAST_SEEN_AT, Now.instant());
        kuifje.set(LinkedServerModel.APPS, 12);
        kuifje.set(LinkedServerModel.SITES, 6);
        kuifje.set(LinkedServerModel.IN_STEP_SINCE, Now.instant());
        Models.get(LinkedServerModel.class).insert(kuifje, Change.PLAIN);
        String peer = ClusterRecords.peerKey(kuifje);
        // One app more than a names page lists (LinkedServerPage.NAMES_PER_PAGE, 50), keyed so their order is theirs.
        List<SharedRow> apps = new ArrayList<>();
        for (int at = 1; at <= 51; at++) {
            String number = String.format("%02d", at);
            apps.add(new SharedRow("app-" + number, null, "kuifje-app-" + number, "{}"));
        }
        // What the master keeps, taken through core's own keeping side from a wire that stands for Kuifje.
        PeerRecords.sync(peer, new RememberedWire(Map.of(SiteModel.MODEL_ID, List.of(
                new SharedRow("1", null, "kuifje-blog", "{}"), new SharedRow("2", null, "kuifje-shop", "{}")),
            DnsZoneModel.MODEL_ID, List.of(new SharedRow("1", null, "kuifje.example.test", "{}")),
            InstanceModel.MODEL_ID, apps,
            ServerModel.MODEL_ID, List.of(new SharedRow("1", null, "kuifje-host", "{}")),
            InstanceTemplateModel.MODEL_ID, List.of(new SharedRow("1", null, "WordPress", "{}")))));
        try {
            // 1. The list shows every server's own counts: this server's read now, Kuifje's as it last said.
            navigateToApp("/admin/" + HohenheimSlugs.LINKED_SERVERS);
            waitForHydration();
            assertThat(page.locator("pl-table-head[data-column='apps']").textContent()).as("step 1: Apps")
                .contains("Apps");
            assertThat(page.locator("pl-table-head[data-column='sites']").textContent()).as("step 1: Sites")
                .contains("Sites");
            Locator row = page.locator("pl-table-row").filter(new Locator.FilterOptions().setHasText("Kuifje"));
            assertThat(row.locator("pl-table-cell[data-column='apps']").textContent().trim())
                .as("step 1: Kuifje's own apps").isEqualTo("12");
            assertThat(row.locator("pl-table-cell[data-column='sites']").textContent().trim())
                .as("step 1: Kuifje's own sites").isEqualTo("6");
            shoot("servers-counts");

            // 2. Its name opens its page under a trail naming the Servers cluster once: its facts, then what this
            //    server remembers of it, kind by kind in the reader's words (apps, hosts and templates, never the
            //    models' own names), the cards one under the other and how changes arrive in a line under them.
            row.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Kuifje")).first().click();
            page.waitForCondition(() -> page.url().contains("/" + HohenheimSlugs.LINKED_SERVERS + "/"));
            waitForHydration();
            String serverPage = URI.create(page.url()).getPath();
            assertThat(page.locator("[data-cms-trail] pl-breadcrumb-item").allTextContents().stream()
                .map(String::trim).toList()).as("step 2: Servers once, then the server")
                .containsExactly("Servers", "Kuifje");
            String content = page.content();
            assertThat(content).as("step 2: its facts").contains("https://kuifje.example.test")
                .contains("Managed from this server since");
            assertThat(content).as("step 2: what this server remembers").contains("What Robbedoes remembers of Kuifje")
                .contains("In step since");
            Locator kinds = page.locator("[data-hh-remembered]");
            assertThat(kindCount(kinds, "Sites")).as("step 2: the sites kept").isEqualTo("2");
            assertThat(kindCount(kinds, "DNS zones")).as("step 2: the DNS zones kept").isEqualTo("1");
            assertThat(kindCount(kinds, "Apps")).as("step 2: the apps kept").isEqualTo("51");
            assertThat(kindCount(kinds, "Hosts")).as("step 2: the hosts kept").isEqualTo("1");
            assertThat(kindCount(kinds, "Templates")).as("step 2: the templates kept").isEqualTo("1");
            assertThat(kinds.textContent()).as("step 2: no kind is named by its model").doesNotContain("Instance")
                .doesNotContain("Server");
            assertThat(kinds.locator("pl-table-row:has(pl-table-cell)").all().stream()
                .map(kind -> kind.locator("pl-table-cell").first().textContent().trim()).toList())
                .as("step 2: the kinds in the sidebar's order (Sites and apps under Apps, then Servers, Domain names,"
                    + " Configure)")
                .containsExactly("Sites", "Apps", "Hosts", "DNS zones", "Templates");
            Locator bands = page.locator(".hh-dashboard-band");
            assertThat(bands.count()).as("step 2: the facts, the remembered card and the line under them")
                .isEqualTo(3);
            assertThat(bands.nth(1).boundingBox().y).as("step 2: the remembered card stands under the facts")
                .isGreaterThanOrEqualTo(bands.nth(0).boundingBox().y + bands.nth(0).boundingBox().height);
            Locator note = page.locator(".hh-band-note");
            BoundedAssertions.assertThat(note).isVisible();
            assertThat(note.textContent()).as("step 2: how changes arrive, under the cards")
                .contains("Changes on Kuifje arrive here at once. Robbedoes also checks every minute.");
            assertThat(note.boundingBox().y).isGreaterThanOrEqualTo(bands.nth(1).boundingBox().y
                + bands.nth(1).boundingBox().height);
            assertThat(kinds.textContent()).as("step 2: never inside the card").doesNotContain("Changes on Kuifje");
            shoot("server-detail");

            // 3. A kind opens the names this server remembers of it, by code and name.
            kinds.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Sites")).click();
            Locator names = page.locator("[data-hh-remembered-names]");
            BoundedAssertions.assertThat(names).isVisible();
            assertThat(names.textContent()).as("step 3: the remembered names")
                .contains("Sites Robbedoes remembers of Kuifje").contains("kuifje-blog").contains("kuifje-shop");
            shoot("server-remembered");

            // 4. More names than a page lists: the first page holds fifty, Next page the rest.
            navigateToApp(serverPage);
            waitForHydration();
            page.locator("[data-hh-remembered]").getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName("Apps").setExact(true)).click();
            BoundedAssertions.assertThat(names).containsText("Apps Robbedoes remembers of Kuifje");
            assertThat(names.textContent()).as("step 4: the first page").contains("kuifje-app-01")
                .contains("kuifje-app-50").doesNotContain("kuifje-app-51");
            // A routed pl-button is drawn as a link.
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next page")).click();
            BoundedAssertions.assertThat(names).containsText("kuifje-app-51");
            assertThat(names.textContent()).as("step 4: the second page").doesNotContain("kuifje-app-01");
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next page")).count())
                .as("step 4: the last page offers no next one").isZero();

            // 5. Kuifje was restored to an earlier state: the dashboard names it among what needs attention.
            Models.get(LinkedServerModel.class).find()
                .where(LinkedServerModel.ID.eq(kuifje.get(LinkedServerModel.ID)))
                .assign(LinkedServerModel.WENT_BACK, SiteModel.MODEL_ID.toString()).updateAll();
            navigateToApp("/admin/dashboard");
            waitForHydration();
            Locator restored = page.locator(".hh-attention-item")
                .filter(new Locator.FilterOptions().setHasText("Kuifje was restored to an earlier state"));
            BoundedAssertions.assertThat(restored).isVisible();
            assertThat(restored.textContent()).as("step 5: no differences counted in this wave")
                .doesNotContain("differences");
            Locator attention = page.locator(".hh-attention").first();
            attention.scrollIntoViewIfNeeded();
            attention.screenshot(new Locator.ScreenshotOptions().setPath(OUT.resolve("attention-restored.png")));
        } finally {
            PeerRecords.forget(peer);
            Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.eq(kuifje.get(LinkedServerModel.ID)))
                .delete();
        }
    }

    /** @return the count the remembered kinds' table shows beside one kind */
    private static String kindCount(Locator kinds, String kind) {
        return kinds.locator("pl-table-row").filter(new Locator.FilterOptions().setHasText(kind))
            .locator("pl-table-cell").nth(1).textContent().trim();
    }

    private void shoot(String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(OUT.resolve(name + ".png")).setFullPage(true));
    }

    /** A wire that stands for a linked server holding these rows, each kind at generation 1. */
    private record RememberedWire(Map<Identifier, List<SharedRow>> held) implements PeerWire {

        @Override
        public Map<Identifier, Long> generations() {
            Map<Identifier, Long> generations = new LinkedHashMap<>();
            this.held.keySet().forEach(model -> generations.put(model, 1L));
            return generations;
        }

        @Override
        public SharedRowsPage rows(Identifier model, String after, int limit) {
            return new SharedRowsPage(1L, 1L, this.held.getOrDefault(model, List.of()), null);
        }
    }
}
