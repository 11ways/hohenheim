package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.BanParts;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Security admin surfaces: ban list + manual-ban form (with private-IP
 * refusal) + lift action, and the dashboard's security band (active-bans stat
 * plus the bans-created chart over the ban model).
 */
class SecurityAdminTest extends HohenheimTestBase {

    /** Manual ban creation with its refusals, the ban list, and the lift row action. */
    @Test
    void banAdminJourney() throws Exception {
        var response = adminPostForm("/admin/bans/new",
            "ip=203.0.113.77&reason=scanner&duration=7d");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row created = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.77")).first();
        assertThat(created).isNotNull();
        assertThat(created.get(BanModel.ACTIVE)).isTrue();
        assertThat(created.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_MANUAL);
        assertThat(created.get(BanModel.REASON)).isEqualTo("scanner");
        assertThat(created.get(BanModel.EXPIRES_AT)).isAfter(Now.instant().plusSeconds(6 * 86400));

        var privateIp = adminPostForm("/admin/bans/new", "ip=192.168.1.1&duration=24h");
        // Validation failure re-renders the form (no redirect) and creates nothing.
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.168.1.1")).count()).isZero();
        assertThat(privateIp.statusCode()).isEqualTo(200);

        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("203.0.113.66", "198.51.100.0/24"));
        try {
            for (String ip : new String[] {"203.0.113.66", "198.51.100.9"}) {
                var allowlisted = adminPostForm("/admin/bans/new", "ip=" + ip + "&duration=24h");
                // Validation failure re-renders the form and creates nothing.
                assertThat(allowlisted.statusCode()).isEqualTo(200);
                assertThat(Models.get(BanModel.class).find()
                    .where(BanModel.IP.eq(ip)).count()).as("ip %s", ip).isZero();
            }
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        }

        navigateToApp("/admin/bans");
        waitForHydration();
        String content = page.locator("body").textContent();
        assertThat(content).contains("203.0.113.77");
        assertThat(content).contains("Lift");

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.77")).first();

        // The source column renders the enum's DECLARED label ("manual" scope ban_source
        // resolves to "Manual"), never the stored value; the stored value itself is
        // asserted off the row above.
        String rowText = page.locator("pl-table-row[data-row-key='" + ban.get(BanModel.ID) + "']")
            .textContent();
        assertThat(rowText).contains("Manual");
        // The lift is the placed operation, posted to the one invoke route with the ban as its subject.
        var lift = adminPostForm(CmsRoutes.invoke(HohenheimSlugs.ADMIN, "bans", BanParts.LIFT.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf((Object) ban.get(BanModel.ID))).toUrl(), confirmed(""));
        assertThat(lift.statusCode()).isIn(200, 302, 303);

        Row lifted = Models.get(BanModel.class).findById(ban.get(BanModel.ID));
        assertThat(lifted.get(BanModel.ACTIVE)).isFalse();
        assertThat(lifted.get(BanModel.LIFTED_BY)).isNotNull();
    }

    /** The dashboard counts the board's four (bans are the Blocked addresses list's), and draws NO 30-day chart. */
    @Test
    void dashboardShowsTheBoardsTilesAndNotTheBansChart() {
        // The stat tiles belong to a fleet with apps (an empty install shows the onboarding hero instead), so the
        // test seeds its own app rather than relying on what earlier test classes left behind.
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Security dashboard app", "security-dashboard-app",
            Map.of("forward_host", "127.0.0.1", "forward_port", 9));
        try {
            navigateToApp("/admin/dashboard");
            waitForHydration();
            // 1. Board Main's tiles are Apps, Hosts, Certificates and Backups: the active-bans tile is gone with Sites
            //    and Access lists (D10a), and the blocked addresses are counted on their own list.
            assertThat(page.locator(".widget-stat-link a.stat-link[href='/admin/apps']").count())
                .as("step 1: the apps are counted").isEqualTo(1);
            assertThat(page.locator(".widget-stat-link a.stat-link[href='/admin/bans']").count())
                .as("step 1: the bans are not").isZero();
            // The deleted security-events surface is gone from the dashboard.
            assertThat(page.locator(".widget-stat-link a.stat-link[href='/admin/security-events']").count())
                .isZero();
            // The 30-day bans chart was REMOVED from the landing dashboard on purpose: on any
            // fleet that is not under attack it is an all-zero series drawn as ~450px of flat
            // line, above the content the operator opened the page for. The count is the
            // Blocked addresses list's. See the AIDEV-NOTE in AdminDashboard.widgets().
            assertThat(page.locator(".widget-chart pl-chart").count()).isZero();
        } finally {
            HardDeletes.row(Models.get(SiteModel.class), site);
        }
    }
}
