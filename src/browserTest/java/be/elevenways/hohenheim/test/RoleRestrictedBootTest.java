package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.task.MonitorStacks;
import be.elevenways.hohenheim.server.task.ReclaimDockerImages;
import be.elevenways.hohenheim.server.task.ResignDnssecZones;
import be.elevenways.hohenheim.server.task.SecuritySweep;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.SessionCookies;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE role journey: a DNS-only appliance boots green through the REAL
 * {@code ServerMain.main} with no proxy, no Docker, no process monitor and no
 * firewall -- and every omitted subsystem is honestly ABSENT (null server,
 * missing route, zero docker constructions), never a false-red FAILED state.
 *
 * Standalone (no shared-server tag) on purpose: it re-points the global
 * database and settings and boots its own runtime, and both the Panel.entries()
 * memoization and the HohenheimRoles snapshot must be clean for this JVM.
 */
// Solo: it declares a DNS-only role set and runs the real ServerMain.main, so it needs a
// virgin HohenheimRoles snapshot and virgin Panel.entries() memoization -- neither of which
// any later class in the same JVM could get back.
@Tag("solo")
class RoleRestrictedBootTest {

    @Test
    void dnsOnlyApplianceBootsGreenWithEverythingElseHonestlyAbsent() throws Exception {
        // 1. Declare the appliance's role set BEFORE boot: dns on, everything
        //    else off. ServerMain.main loads the (empty) settings file over
        //    these programmatic values and snapshots them into HohenheimRoles.
        //    Enumerated, never hand-listed: every role defaults to ENABLED, so a
        //    hand-list silently leaves any later-added role on -- the INSTANCES
        //    role did exactly that and put a DockerClient construction into this
        //    "docker-less" boot.
        HohenheimSettingsBoot.forceDefinitions();
        for (HohenheimRoles.Role role : HohenheimRoles.Role.values()) {
            Zenit.SETTINGS_VALUES.setValue(role.setting(),
                role == HohenheimRoles.Role.DNS);
        }

        // Private database; the admin listener is started manually on port 0.
        TestDatabases.freshDatabase();
        ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);

        // 2. The REAL production entry point, not a harness reconstruction.
        ServerMain.main(new String[0]);

        assertThat(ServerZenitRuntime.isReady())
            .as("step 2: a DNS-only boot must come up green")
            .isTrue();

        // 3. The proxy is ABSENT (null), never a constructed server in FAILED
        //    state: absence is a role decision, failure is an incident.
        assertThat(ServerMain.getProxyServer())
            .as("step 3: roles.proxy=false means getProxyServer() is null (ABSENT), not FAILED")
            .isNull();
        assertThat(ServerMain.getDnsServer())
            .as("step 3: the DNS subsystem, the one enabled role, IS constructed")
            .isNotNull();

        // 4. No Docker socket was ever touched: with stacks and databases off,
        //    not a single DockerClient may have been constructed, and the boot
        //    probe reports DISABLED (a declared absence, not UNPROBED silence).
        assertThat(DockerClient.constructionCount())
            .as("step 4: a docker-less role set never constructs a DockerClient")
            .isZero();
        assertThat(DockerHealth.instance().status())
            .as("step 4: the boot probe answers DISABLED for a stacks-less node")
            .isEqualTo(DockerHealth.Status.DISABLED);

        // 5. The admin panel carries ONLY the DNS-and-core peers.
        Panel admin = PanelRegistry.getBySlug("admin");
        assertThat(admin).as("step 5: the admin panel is registered").isNotNull();
        // Every entry: Panel.peers() lists legacy peers only, so a parts entry passed both checks unseen.
        List<String> slugs = admin.entries().stream().map(PanelEntry::slug).toList();
        assertThat(slugs)
            .as("step 5: DNS, settings and the core peers are present")
            .contains("dashboard", "dns-zones", "dns-records", "settings", "activity", "users");
        assertThat(slugs)
            .as("step 5: disabled roles' peers are gone from the peer list")
            .doesNotContain("sites", "domains", "certificates", "stacks",
                "databases", "database-engines", "instances", "servers", "bans", "spamservice");

        // 6. Omitted slugs 404 over real HTTP -- the routes are GONE, not merely
        //    hidden from the nav. The enabled peers answer 200 with the same
        //    session, which is what makes the 404 a route fact, not an auth veil.
        ZenitHttpServer server = ServerZenitRuntime.createServer(0);
        server.start();
        try {
            int port = server.getPort();
            String session = HohenheimTestBase.seedAuthenticatedAdmin();
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();

            assertThat(statusOf(client, port, session, "/admin/dns-zones"))
                .as("step 6: the DNS zones page answers 200 for an admin").isEqualTo(200);
            assertThat(statusOf(client, port, session, "/admin/settings"))
                .as("step 6: the settings page answers 200 for an admin").isEqualTo(200);
            for (String gone : List.of("/admin/stacks", "/admin/databases",
                    "/admin/instances", "/admin/servers", "/admin/sites", "/admin/bans")) {
                assertThat(statusOf(client, port, session, gone))
                    .as("step 6: %s must 404 (route gone), not render or redirect", gone)
                    .isEqualTo(404);
            }

            // 6b. The /api/v1 write lanes of the absent tiers answer the API's uniform 404, the
            //     answer their panel routes give above -- never a 500 from looking up an entry
            //     the role left out. Each target row EXISTS and is visible to the admin key (the
            //     reads answer 200), so the 404 is the missing entry, not the row lookup.
            int adminId = TenantConduits.operatorUser().get(UserModel.ID);
            String key = ApiKeyService.create(adminId, "role-restricted-admin", List.of("hohenheim.*"), null)
                .plaintext();
            int siteId = site();
            int domainId = domain(siteId);
            int listId = accessList();
            int instanceId = instance();
            assertThat(apiSend(client, port, key, "GET", "/api/v1/sites/" + siteId, "").statusCode())
                .as("step 6b: the site read lane stays open on a proxy-less node").isEqualTo(200);
            assertThat(apiSend(client, port, key, "GET", "/api/v1/access-lists/" + listId, "").statusCode())
                .as("step 6b: the access-list read lane stays open on a proxy-less node").isEqualTo(200);
            int rulesBefore = Models.get(AccessRuleModel.class).findForAccessList(listId).size();
            for (String write : List.of(
                    "/api/v1/sites",
                    "/api/v1/sites/" + siteId + "/delete",
                    "/api/v1/sites/" + siteId + "/domains",
                    "/api/v1/sites/" + siteId + "/domains/" + domainId + "/delete",
                    "/api/v1/access-lists",
                    "/api/v1/access-lists/" + listId + "/delete",
                    "/api/v1/access-lists/" + listId + "/rules",
                    "/api/v1/instances",
                    "/api/v1/instances/" + instanceId + "/delete")) {
                HttpResponse<String> answer = apiSend(client, port, key, "POST", write,
                    "name=role-restricted-write&type=ip");
                assertThat(answer.statusCode())
                    .as("step 6b: POST %s answers the uniform 404 of an absent tier: %s", write, answer.body())
                    .isEqualTo(404);
            }
            assertThat(Models.get(SiteModel.class).findById(siteId))
                .as("step 6b: the refused delete left the site in place").isNotNull();
            assertThat(Models.get(SiteDomainModel.class).findById(domainId))
                .as("step 6b: the refused delete left the domain in place").isNotNull();
            assertThat(Models.get(AccessListModel.class).findById(listId))
                .as("step 6b: the refused delete left the access list in place").isNotNull();
            assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId))
                .as("step 6b: the refused rule create birthed no node").hasSize(rulesBefore);
            assertThat(Models.get(InstanceModel.class).findById(instanceId))
                .as("step 6b: the refused delete left the instance in place").isNotNull();

            // The template lane is a create too: naming an EXISTING template must not ride
            // the template funnel around the absent entry, so it answers the same 404 and
            // no instance row is born.
            int templateId = template();
            long instancesBefore = Models.get(InstanceModel.class).find().withTrashed().count();
            HttpResponse<String> templated = apiSend(client, port, key, "POST", "/api/v1/instances",
                "name=role-restricted-templated&template_id=" + templateId);
            assertThat(templated.statusCode())
                .as("step 6b: POST /api/v1/instances with a template_id answers the uniform 404: %s",
                    templated.body())
                .isEqualTo(404);
            assertThat(Models.get(InstanceModel.class).find().withTrashed().count())
                .as("step 6b: the refused template create birthed no instance").isEqualTo(instancesBefore);
        } finally {
            server.stop();
        }

        // 7. The schedule catalog is role-shaped: only the tasks whose role is
        //    on (plus the role-free ones) own a system_task row; every docker,
        //    database, process, proxy and firewall task retracted its schedule.
        List<String> taskTypes = ServerMain.getTaskService().taskModel()
            .findAllSystemRows().stream()
            .map(r -> (String) r.get(SystemTaskModel.TYPE))
            .toList();
        assertThat(taskTypes)
            .as("step 7: role-free and DNS tasks are scheduled")
            .contains(typeOf(BackupControlPlane.class), typeOf(ResignDnssecZones.class));
        assertThat(taskTypes)
            .as("step 7: disabled roles' tasks declared no schedules")
            .doesNotContain(
                typeOf(MonitorStacks.class),
                typeOf(ReclaimDockerImages.class),
                typeOf(BackupDatabases.class),
                typeOf(UpdateSystemUsers.class),
                typeOf(SecuritySweep.class),
                typeOf(CleanOrphanCertificates.class),
                typeOf(UpdateSystemIpAddresses.class));
    }

    /** @return the stored task type the catalog writes for a task class (its id, never the class name) */
    private static String typeOf(Class<?> taskClass) {
        return TaskCatalog.currentSpelling(taskClass.getName());
    }

    /**
     * Shut down the appliance this class booted, so its worker JVM can actually exit.
     *
     * AIDEV-NOTE: this class starts the REAL ServerMain and used to stop nothing. The DNS
     * server and the task scheduler are non-daemon threads, so the fork stayed alive after
     * the last assertion and Gradle sat waiting for it: with forkEvery=1 the whole solo lane
     * spent 40 of its 79 seconds AFTER its final test finished, for six tests. Measured by
     * comparing the JUnit XML timestamps against the task duration in the Gradle profile --
     * the test report looked perfectly healthy the whole time, because the cost was entirely
     * outside any test.
     */
    @AfterAll
    static void stopTheAppliance() {
        ServerZenitRuntime.stop();
    }

    /** A static site row, stored through the model as SiteApiTest's fixture does. */
    private static int site() {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, "role-restricted-site");
        row.set(SiteModel.SLUG, "role-restricted-site");
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, true);
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(Map.of("root_path", "/tmp/role-restricted-site")));
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, "role-restricted.test");
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        Models.get(SiteDomainModel.class).save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int accessList() {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, "role-restricted-list");
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    /** A created (never deployed) instance row, InstanceApiTest's fixture shape. */
    private static int instance() {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "role-restricted-instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A template the admin key may select, so only the absent entry can refuse the create. */
    private static int template() {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, "role-restricted-template");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    /** One API-key request with a urlencoded body, redirects never followed. */
    private static HttpResponse<String> apiSend(HttpClient client, int port, String key, String method, String path,
                                                String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("X-Api-Key", key)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int statusOf(HttpClient client, int port, String session, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Cookie", SessionCookies.name() + "=" + session)
            .GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
