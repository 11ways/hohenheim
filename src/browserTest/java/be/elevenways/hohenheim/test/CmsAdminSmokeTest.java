package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the zenit-cms admin panel: the shell renders, the sidebar
 * carries the resources, and the dashboard is the landing page.
 */
class CmsAdminSmokeTest extends HohenheimTestBase {

    @Test
    void panelShellSidebarListsAndSettingsRender() {
        navigateToApp("/admin");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Hohenheim");
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);

        // The boards' eight entries; AdminNavigationJourneyTest owns the full inventory, the clusters' members and
        // the reachability of everything demoted.
        String sidebar = page.locator("pl-app-sidebar").textContent();
        for (String entry : java.util.List.of("Dashboard", "Apps", "Databases", "Hosts", "Domains", "Access",
                "Activity", "Settings")) {
            assertThat(sidebar).as("the sidebar names " + entry).contains(entry);
        }
        assertThat(sidebar).as("what an app is made of is reached through Apps, not the sidebar")
            .doesNotContain("Sites")
            .doesNotContain("Instances")
            .doesNotContain("Sign-in providers");

        // The zenit-auth resources are wired into THIS panel, as tabs of the Access cluster. Their own behaviour
        // (create/edit/toggle/grants/roles journeys) is zenit-auth's to prove -- AuthCmsResourcesIntegrationTest.
        page.locator("pl-app-sidebar a[href='/admin/" + HohenheimSlugs.Cluster.ACCESS + "']").click();
        page.waitForCondition(() -> page.locator("[data-cms-cluster-tabs] a[href='/admin/users']").count() > 0);
        assertThat(page.locator("[data-cms-cluster-tabs] a[href='/admin/roles']").count())
            .as("zenit-auth's roles resource is mounted in the hohenheim panel")
            .isGreaterThan(0);

        // Soft nav to the Apps list keeps the page cost down.
        page.locator("pl-app-sidebar a[href='/admin/apps']").click();
        page.waitForCondition(() -> {
            var el = page.querySelector("h1");
            return el != null && el.textContent().contains("Apps");
        });
        assertThat(page.content()).contains("Apps");

        navigateToApp("/admin/settings");
        waitForHydration();

        content = page.content();
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Let's Encrypt");
    }
}
