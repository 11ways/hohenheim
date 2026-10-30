package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.ChangeFactModel;
import be.elevenways.zenit.common.orm.history.ChangeModel;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.test.support.HistoryFixtureRows;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.SystemPrincipal;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * The two landing dashboards answer every audience over HTTP as their panels gate them, and each admitted audience's
 * dashboard offers its stored surface.
 *
 * AIDEV-NOTE: {@code /panel-surfaces/landing-dashboards.txt} holds the typed captures of the admitted audiences
 * (PanelSurfaces: heading, header links, the drawn widget tree with each widget's config and data shape, no markup and
 * no data values). A failing comparison is a changed landing surface: read the difference first. Only a change that
 * is deliberate and proven at its home is re-recorded, by copying the live set SurfaceBaselines wrote to
 * {@code build/panel-surfaces/} over the file in the same commit; a missing resource records the same way. The status,
 * redirect, title and dashboard link of every audience are {@link #LANDINGS}; what each widget draws is proven at its
 * own journey (DashboardAttentionJourneyTest, OnboardingChecklistTest, DashboardRoleGatingTest, ManagePanelJourneyTest).
 *
 * AIDEV-NOTE: the fixture decides which widgets the operator's tree draws and how many items their data holds. Its
 * only site has no address and so serves nobody: "Put your first app online" stays TODO with its Open link and the
 * Apps tile reads "1 with a problem". Its never-checked host makes the Hosts tile "1 waiting". The open admission and
 * backups steps present the attention items stating their stage, so the attention band, holding nothing else, is not
 * drawn under the open checklist.
 *
 * AIDEV-NOTE: the attention widget reads JVM-global inputs other classes of a lane change (so: pin
 * every input the test does not own, never compare host state). {@link #pinAttentionInputs} sets the settings and
 * servers and restores them after (the DNS zones follow this class's datasource by themselves); Docker health and
 * the Spamservice manager change only in a real ServerMain boot, which runs solo, so they are asserted untouched.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class LandingDashboardSurfacesTest extends HohenheimHttpTestBase {

    private static final String BASELINE = "/panel-surfaces/landing-dashboards.txt";
    private static final String OPERATOR = "operator";
    private static final String TENANT = "tenant";
    private static final String OUTSIDER = "outsider";
    private static final String ADMIN_DASHBOARD = "/admin/dashboard";
    private static final String MANAGE_DASHBOARD = "/manage/dashboard";
    private static final String ADMIN_TITLE = "Dashboard";
    private static final String MANAGE_TITLE = "Your services";

    /** The one row the people-only Recent feeds draw from {@link #seedRecentActivity}'s mixed history. */
    private static final String RECENT_ROW = ">Someone not signed in created landing-site<";

    private static final List<Landing> LANDINGS = List.of(
        Landing.redirect(OPERATOR, "/admin", ADMIN_DASHBOARD),
        Landing.page(OPERATOR, ADMIN_DASHBOARD, ADMIN_TITLE),
        Landing.redirect(OPERATOR, "/manage", MANAGE_DASHBOARD),
        Landing.page(OPERATOR, MANAGE_DASHBOARD, MANAGE_TITLE),
        Landing.refused(TENANT, "/admin"),
        Landing.refused(TENANT, ADMIN_DASHBOARD),
        Landing.redirect(TENANT, "/manage", MANAGE_DASHBOARD),
        Landing.page(TENANT, MANAGE_DASHBOARD, MANAGE_TITLE),
        Landing.refused(OUTSIDER, "/admin"),
        Landing.refused(OUTSIDER, ADMIN_DASHBOARD),
        Landing.refused(OUTSIDER, "/manage"),
        Landing.refused(OUTSIDER, MANAGE_DASHBOARD));

    /** What one audience gets from one path; a page carries its title, a redirect its location. */
    private record Landing(@NonNull String audience, @NonNull String path, int status, @Nullable String location,
                           @Nullable String title) {

        static @NonNull Landing page(@NonNull String audience, @NonNull String path, @NonNull String title) {
            return new Landing(audience, path, 200, null, title);
        }

        static @NonNull Landing redirect(@NonNull String audience, @NonNull String path, @NonNull String location) {
            return new Landing(audience, path, 302, location, null);
        }

        static @NonNull Landing refused(@NonNull String audience, @NonNull String path) {
            return new Landing(audience, path, 403, null, null);
        }
    }

    private static UUID tenantId;

    private static TestSession tenant;
    private static TestSession outsider;

    private static @Nullable String backupTarget;
    private static @Nullable Boolean dnsEnabled;
    private static @Nullable Boolean sshWatch;
    private static @Nullable ProxyServer proxyServer;
    private static @Nullable DnsServer dnsServer;

    @BeforeAll
    static void seed() throws Exception {
        freshSeededDatabase();
        tenantId = ApiSupport.user("landing-tenant@hohenheim.local", "Landing Tenant");
        UUID outsiderId = ApiSupport.user("landing-outsider@hohenheim.local", "Landing Outsider");
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "landing-site");
        site.set(SiteModel.SLUG, "landing-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimCapabilities.MANAGE, true);
        tenant = sessionFor(tenantId);
        outsider = sessionFor(outsiderId);
        seedRecentActivity(site.get(SiteModel.ID));
        pinAttentionInputs();
    }

    /** Pin the dashboard attention's JVM-global inputs to the stored capture's: no proxy, no DNS, no SSH watch. */
    private static void pinAttentionInputs() {
        backupTarget = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        dnsEnabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        sshWatch = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.SSH_WATCH_ENABLED);
        proxyServer = ServerMain.getProxyServer();
        dnsServer = ServerMain.getDnsServer();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, null);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.ENABLED, false);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, false);
        ServerMain.adoptProxyServer(null);
        ServerMain.adoptDnsServer(null);
        assertThat(DockerHealth.instance().status()).as("setup: no boot probed the shared Docker health")
            .isEqualTo(DockerHealth.Status.UNPROBED);
        assertThat(SpamserviceManager.get().snapshot().needsAttention())
            .as("setup: no boot started the shared Spamservice manager").isFalse();
    }

    @AfterAll
    static void restoreAttentionInputs() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, backupTarget);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Dns.ENABLED, dnsEnabled);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.SSH_WATCH_ENABLED, sshWatch);
        ServerMain.adoptProxyServer(proxyServer);
        ServerMain.adoptDnsServer(dnsServer);
    }

    /**
     * Seed a fixed mixed-provenance history instead of capturing boot seeders and the host's OS accounts.
     *
     * AIDEV-NOTE: each action names its model by the model's own id and its origin by the origin's own token, never a
     * literal: the literals spelled the pre-sweep 'zenit-auth:' namespace and the v1 origins ("web", not "zenit:web"),
     * so the role grant escaped the activity scope's internal models and the person's action left the people-only
     * feed. The one web action is the only person's, so the band shows it and nothing else; it creates the landing
     * site, because a history line names its record by the live title. The rows are written straight into the
     * history tables (HistoryFixtureRows) because their origins and actors must be chosen, which the writer derives
     * from whatever lane calls it.
     */
    private static void seedRecentActivity(@NonNull Object siteId) {
        Datasource datasource = Models.get(SiteModel.class).getResolvedDatasource();
        new ChangeFactModel(datasource).find().delete();
        new ChangeModel(datasource).find().delete();
        new ActionModel(datasource).find().delete();
        var now = Now.instant();
        Object[][] records = {
            {SiteModel.MODEL_ID, siteId, AccountabilityOrigin.WEB},
            {RecordGrantModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SiteModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {InstanceQuotaModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {GrantModel.MODEL_ID, null, AccountabilityOrigin.UNATTRIBUTED},
            {UserModel.MODEL_ID, null, AccountabilityOrigin.UNATTRIBUTED},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM},
            {SystemUserModel.MODEL_ID, null, AccountabilityOrigin.SYSTEM}
        };
        for (int index = 0; index < records.length; index++) {
            Object[] fixture = records[index];
            AccountabilityOrigin origin = (AccountabilityOrigin) fixture[2];
            HistoryFixtureRows.Action action = HistoryFixtureRows.action(datasource).at(now.minusSeconds(index))
                .origin(origin.token());
            if (origin == AccountabilityOrigin.SYSTEM) {
                action.by(SystemPrincipal.INSTANCE.reference());
            }
            action.change((Identifier) fixture[0], fixture[1] == null ? "landing-fixture-" + index : fixture[1],
                ChangeOp.CREATE).done().write();
        }
    }

    @Test
    void everyAudienceGetsItsLandingDashboard() {
        // Both proofs always run, so one run names every difference and a missing resource still gets its live set.
        assertAll(this::everyAudienceIsAnsweredOverHttp, this::everyAdmittedAudienceOffersTheStoredSurface);
    }

    private void everyAudienceIsAnsweredOverHttp() throws Exception {
        // 1. Each audience asks both panels' index and dashboard route: the operator, a tenant managing one site and a
        //    signed-in principal holding nothing.
        Map<String, String> sessions = Map.of(OPERATOR, sessionToken, TENANT, tenant.token(),
            OUTSIDER, outsider.token());
        for (Landing landing : LANDINGS) {
            String asked = landing.audience() + " " + landing.path();
            HttpResponse<String> response = httpGet(landing.path(), sessions.get(landing.audience()));
            assertThat(response.statusCode()).as("step 1: %s status", asked).isEqualTo(landing.status());
            assertThat(response.headers().firstValue("Location").orElse(null)).as("step 1: %s location", asked)
                .isEqualTo(landing.location());
            if (landing.title() != null) {
                assertThat(response.body()).as("step 1: %s page", asked)
                    .contains("<title>" + landing.title() + "</title>", "href=\"" + landing.path() + "\"",
                        "<zn-widget-surface", RECENT_ROW)
                    .doesNotContain("landing-fixture");
            }
        }
    }

    private void everyAdmittedAudienceOffersTheStoredSurface() {
        // 2. Each admitted audience's dashboard equals its stored capture, fact by fact.
        SurfaceBaselines stored = SurfaceBaselines.load(LandingDashboardSurfacesTest.class, BASELINE);
        AccessContext tenantAccess = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Landing Tenant")));
        stored.check(PanelSurfaces.capture(SurfaceCase.of(HohenheimSlugs.ADMIN, HohenheimSlugs.DASHBOARD, OPERATOR,
            TenantConduits.operator())));
        stored.check(PanelSurfaces.capture(SurfaceCase.of(HohenheimSlugs.MANAGE, HohenheimSlugs.DASHBOARD, OPERATOR,
            TenantConduits.operator())));
        stored.check(PanelSurfaces.capture(SurfaceCase.of(HohenheimSlugs.MANAGE, HohenheimSlugs.DASHBOARD, TENANT,
            tenantAccess)));

        // 3. Every stored case was captured and nothing else was.
        stored.finish();
    }
}
