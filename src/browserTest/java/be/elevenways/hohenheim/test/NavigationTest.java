package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * Navigation through the zenit-cms admin shell: sidebar links, soft
 * navigation, back button, and shell layout.
 */
class NavigationTest extends HohenheimTestBase {

    private void waitForHeading(String expected) {
        try {
            page.waitForCondition(() -> {
                var el = page.querySelector("h1");
                return el != null && el.textContent().contains(expected);
            });
        } catch (TimeoutError e) {
            fail("no h1 read '" + expected + "' at " + page.url() + "; the h1s read "
                + page.locator("h1").allTextContents(), e);
        }
    }

    /** Waits for a cluster member's landing: the cluster's name as the h1 and the member as the active tab. */
    private void waitForClusterPage(String cluster, String member) {
        waitForHeading(cluster);
        var activeTab = page.locator("[data-cms-cluster-tabs] [aria-current='page']").first();
        try {
            page.waitForCondition(() -> activeTab.count() > 0 && activeTab.textContent().contains(member));
        } catch (TimeoutError e) {
            fail("no active cluster tab read '" + member + "' at " + page.url() + "; the active tabs read "
                + page.locator("[data-cms-cluster-tabs] [aria-current='page']").allTextContents(), e);
        }
    }

    @Test
    void shellLayoutSurvivesSoftNavigationAcrossThePanel() {
        navigateToApp("/admin");
        waitForHydration();

        // The dashboard is the landing page and carries the shell chrome.
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        // A site is reached through Apps; the closed command palette draws no entry, so the served page names Sites
        // nowhere visible.
        assertThat(page.locator("pl-app-sidebar").textContent()).as("the sidebar's Apps entry").contains("Apps");

        // The pl-app-shell grid places the sidebar to the left of the content
        // (grid-areas "sidebar content"), so they sit on the same row.
        var sidebar = page.locator("pl-app-sidebar").boundingBox();
        var main = page.locator("pl-app-content").boundingBox();
        assertThat(sidebar).isNotNull();
        assertThat(main).isNotNull();
        assertThat(sidebar.x + sidebar.width)
            .as("sidebar right edge should be at or before main left edge")
            .isLessThanOrEqualTo(main.x + 1.0);
        assertThat(main.y)
            .as("main content should sit on the same row as the sidebar")
            .isLessThan(sidebar.y + sidebar.height);

        // Soft nav into a cluster: the Domains entry lands on its first member, Addresses, whose page heads with
        // the cluster's name and tabs (the member is the active tab, not a second h1); a tab soft-navigates to a
        // sibling member. The URL, the heading and the shell survive.
        page.locator("pl-app-sidebar a[href='/admin/" + HohenheimSlugs.Cluster.DOMAIN_NAMES + "']").click();
        waitForClusterPage("Domains", "Addresses");
        assertThat(page.url()).endsWith("/admin/domains");
        page.locator("[data-cms-cluster-tabs] a[href='/admin/certificates']").first().click();
        waitForClusterPage("Domains", "Certificates");
        assertThat(page.url()).endsWith("/admin/certificates");
        assertThat(page.locator("h1").count()).as("one h1 on a cluster member's landing").isEqualTo(1);
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");
        // The eight sidebar entries, each a link.
        assertThat(page.locator("pl-app-sidebar a").count()).isGreaterThanOrEqualTo(8);
        // AdminNavigationJourneyTest owns the full inventory and the clusters' members; here the rendered sidebar
        // names each of the eight entries and offers no demoted peer.
        String sidebarText = page.locator("pl-app-sidebar").textContent();
        for (String entry : List.of("Dashboard", "Apps", "Databases", "Hosts", "Domains", "Access", "Activity",
                "Settings")) {
            assertThat(sidebarText).as("the sidebar names " + entry).contains(entry);
        }
        assertThat(sidebarText).as("what an app is made of is reached through Apps, not the sidebar")
            .doesNotContain("Sites")
            .doesNotContain("Instances")
            .doesNotContain("Sign-in providers");

        // Regression: after a soft nav the client renders the list footer itself;
        // filtered short keys ("none" scope=cms target=range) must resolve from
        // the browser bundle, not degrade to their raw key.
        page.waitForCondition(() -> page.locator(".cms-list-count").count() > 0);
        // Allow the async microcopy fill one repaint before judging.
        page.waitForCondition(() -> {
            String text = page.locator(".cms-list-count").textContent().trim();
            return !text.equals("none") && !text.equals("range");
        });
        String count = page.locator(".cms-list-count").textContent().trim();
        assertThat(count).satisfiesAnyOf(
            t -> assertThat(t).contains("No records"),
            t -> assertThat(t).contains("of"));

        // A second soft nav keeps the brand in place.
        page.locator("pl-app-sidebar a[href='/admin/apps']").click();
        waitForHeading("Apps");
        assertThat(page.locator("h1").first().textContent()).contains("Apps");
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");

        // The browser back button restores the previous soft-navigated page.
        page.goBack();
        waitForClusterPage("Domains", "Certificates");
        assertThat(page.url()).endsWith("/admin/certificates");

        // The zenit-auth resources are wired into THIS panel: People is a tab of the Access cluster, and Roles are
        // reached from the People list (AdminNavigationJourneyTest proves that route). Their own behaviour is
        // zenit-auth's to prove -- AuthCmsResourcesIntegrationTest.
        page.locator("pl-app-sidebar a[href='/admin/" + HohenheimSlugs.Cluster.ACCESS + "']").click();
        // The Certificates page already draws the Domains cluster's tabs: wait for the Access cluster's own heading.
        page.waitForCondition(() -> page.locator("h1").first().textContent().contains("Access"));
        assertThat(page.locator("[data-cms-cluster-tabs] a[href='/admin/users']").count())
            .as("zenit-auth's people resource is a tab of the Access cluster")
            .isGreaterThan(0);

        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Settings");
        assertThat(page.content()).contains("Proxy");
        assertThat(page.content()).contains("Security");
        assertThat(page.content()).contains("Let's Encrypt");
    }

    @Test
    void softNavDashboardKeepsStatTitlesIconsAndAttentionEntries() throws Exception {
        // Regression: the dashboard re-renders CLIENT-side over soft navigation.
        // Stat titles are locale-map lookups (they need the payload-seeded locale
        // chain) and typed attention items must ride WidgetInstance runtime
        // data -- the old render-time server-only collector read produced a
        // false "All clear" on every soft nav.
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "softnav-attention-cert");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "softnav.example.test");
        cert.set(CertificateModel.RENEWAL_ERROR, "boom");
        cert.set(CertificateModel.ERROR_COUNT, 3);
        certModel.save(cert);
        // An app, so the dashboard is the running fleet's (attention, stat tiles, apps) rather than a fresh install's
        // lead, which counts nothing on purpose.
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "softnav-app");
        site.set(SiteModel.SLUG, "softnav-app");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        try {
            navigateToApp("/admin/sites");
            waitForHydration();

            page.locator("pl-app-sidebar a[href='/admin/dashboard']").click();
            page.waitForCondition(() -> page.locator(".hh-dashboard-band").count() >= 3);

            // Stat tiles keep their localized titles AND their icons.
            page.waitForCondition(() -> page.locator("pl-stat-card .label").count() >= 3);
            var labels = page.locator("pl-stat-card .label").allInnerTexts();
            assertThat(labels).anySatisfy(label -> assertThat(label).contains("Apps"));
            assertThat(labels).allSatisfy(label -> assertThat(label.trim()).isNotEmpty());
            assertThat(page.locator("pl-stat-card .stat-icon pl-icon").count())
                .isGreaterThanOrEqualTo(3);

            // The attention panel shows the real entries, never a false all-clear.
            assertThat(page.locator(".hh-attention-item[data-severity='error']").count())
                .isGreaterThanOrEqualTo(1);
            assertThat(page.locator(".hh-attention-clear").count()).isZero();
        } finally {
            certModel.delete(cert);
            siteModel.delete(site);
        }
    }
}
