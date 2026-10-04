package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.cms.AdminDashboard;
import be.elevenways.hohenheim.server.cms.ManageDashboard;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelEntryKinds;
import be.elevenways.zenit.cms.common.panel.PanelNav;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two landing dashboards, /admin's and /manage's, as framework dashboard entries: dispatched as the dashboard
 * kind, landed on by their panel's index and rendered through the widget surface host.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class LandingDashboardsTest extends HohenheimTestBase {

    @Test
    void bothLandingDashboardsAreFrameworkDashboardsThePanelIndexLandsOn() throws Exception {
        AccessContext operator = TenantConduits.operator();

        // 1. The admin panel's dashboard entry is the framework dashboard kind, and the index lands on it.
        Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "admin panel");
        PanelEntry adminDashboard = admin.entryBySlug("dashboard");
        assertThat(adminDashboard).as("step 1: /admin/dashboard is the admin dashboard")
            .isInstanceOf(AdminDashboard.class);
        assertThat(adminDashboard.kind()).as("step 1: dispatched as the framework dashboard kind")
            .isSameAs(PanelEntryKinds.DASHBOARD);
        assertThat(PanelNav.landingEntry(admin, operator)).as("step 1: the admin index lands on it")
            .isSameAs(adminDashboard);

        // 2. The same for the manage panel's tenant-scoped dashboard.
        Panel manage = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.MANAGE), "manage panel");
        PanelEntry manageDashboard = manage.entryBySlug("dashboard");
        assertThat(manageDashboard).as("step 2: /manage/dashboard is the manage dashboard")
            .isInstanceOf(ManageDashboard.class);
        assertThat(manageDashboard.kind()).as("step 2: dispatched as the framework dashboard kind")
            .isSameAs(PanelEntryKinds.DASHBOARD);
        assertThat(PanelNav.landingEntry(manage, operator)).as("step 2: the manage index lands on it")
            .isSameAs(manageDashboard);

        // 3. Both render through the framework's widget surface host on their own route.
        for (String path : new String[] {"/admin/dashboard", "/manage/dashboard"}) {
            HttpResponse<String> page = adminGet(path);
            assertThat(page.statusCode()).as("step 3: %s renders", path).isEqualTo(200);
            assertThat(page.body()).as("step 3: %s renders its widgets in the surface host", path)
                .contains("<zn-widget-surface");
        }
    }
}
