package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.CleanOldActivity;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.task.MonitorStacks;
import be.elevenways.hohenheim.server.task.ReclaimDockerImages;
import be.elevenways.hohenheim.server.task.ResignDnssecZones;
import be.elevenways.hohenheim.server.task.SecuritySweep;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE role journey: a DNS-only appliance boots green through the REAL
 * {@code ServerMain.main} with no proxy, no Docker, no process monitor and no
 * firewall -- and every omitted subsystem is honestly ABSENT (null server,
 * missing route, zero docker constructions), never a false-red FAILED state.
 *
 * Standalone (no shared-server tag) on purpose: it re-points the global
 * database and settings and boots its own runtime, and both the Panel.peers()
 * memoization and the HohenheimRoles snapshot must be clean for this JVM.
 */
// Solo: it declares a DNS-only role set and runs the real ServerMain.main, so it needs a
// virgin HohenheimRoles snapshot and virgin Panel.peers() memoization -- neither of which
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
        File settingsDry = File.createTempFile("hohenheim-role-boot", ".dry");
        settingsDry.delete();
        settingsDry.deleteOnExit();
        System.setProperty("hohenheim.settings", settingsDry.getAbsolutePath());
        HohenheimSettingsFiles.forceDefinitions();
        for (HohenheimRoles.Role role : HohenheimRoles.Role.values()) {
            HohenheimSettings.VALUES.setValue(role.setting(),
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
        List<String> slugs = admin.peers().stream().map(PanelPeer::slug).toList();
        assertThat(slugs)
            .as("step 5: DNS, settings and the core peers are present")
            .contains("dashboard", "dns-zones", "dns-records", "settings", "activity", "users");
        assertThat(slugs)
            .as("step 5: disabled roles' peers are gone from the peer list")
            .doesNotContain("sites", "domains", "certificates", "stacks",
                "databases", "instances", "servers", "bans", "spamservice");

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
            .contains(CleanOldActivity.class.getName(), ResignDnssecZones.class.getName());
        assertThat(taskTypes)
            .as("step 7: disabled roles' tasks declared no schedules")
            .doesNotContain(
                MonitorStacks.class.getName(),
                ReclaimDockerImages.class.getName(),
                BackupDatabases.class.getName(),
                UpdateSystemUsers.class.getName(),
                SecuritySweep.class.getName(),
                CleanOrphanCertificates.class.getName(),
                UpdateSystemIpAddresses.class.getName());
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

    private static int statusOf(HttpClient client, int port, String session, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
