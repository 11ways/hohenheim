package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end env injection: a REAL ephemeral Postgres provisioned through the
 * production DatabaseService, linked to a site, and a REAL child process spawned
 * through the production ManagedProcessSiteHandler base -- asserting the child's
 * actual environment carries the derived connection variables. Docker-gated.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class EnvInjectionFlowTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";

    private static ProcessMonitor monitor;
    private static PortAllocator portAllocator;
    private static DatabaseService service;
    private static String dbName;
    private static EnvEchoHandler handler;

    @BeforeAll
    static void boot() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        UpstreamKindHandlers.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        monitor = new ProcessMonitor();
        portAllocator = new PortAllocator();
        service = new DatabaseService();

        netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    private static PrivateNetns netns;

    @AfterAll
    static void cleanup() throws Exception {
        if (handler != null) {
            handler.destroy();
        }
        if (service != null && dbName != null) {
            service.destroy(dbName, true);
        }
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    /** Spawns a child that prints its whole environment, using the REAL injection path. */
    private static final class EnvEchoHandler extends ManagedProcessSiteHandler {

        EnvEchoHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "env-echo-" + siteId, settings, portAllocator, monitor);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            return List.of("sh", "-c", "env; sleep 600");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    @Test
    void spawnedChildReceivesDerivedConnectionVariables() throws Exception {
        dbName = "envflow" + System.nanoTime();
        ManagedDatabase.Connection conn = service.create(dbName, ManagedDatabase.Engine.POSTGRES,
            PG_IMAGE, "appuser", "flowsecret", "flowdb", true);   // ephemeral tmpfs, real container

        // Persisted as active on the DEFAULT datasource -- exactly what injection reads.
        Row dbRow = Models.get(DatabaseModel.class).findByName(dbName);
        assertThat(dbRow).isNotNull();
        assertThat((String) dbRow.get(DatabaseModel.STATUS)).isEqualTo(DatabaseModel.STATUS_ACTIVE);

        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "env-flow-site");
        site.set(SiteModel.SLUG, "env-flow-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        Integer siteId = site.get(SiteModel.ID);

        SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(SiteDatabaseModel.SITE_ID, siteId);
        link.set(SiteDatabaseModel.DATABASE_ID, dbRow.get(DatabaseModel.ID));
        link.set(SiteDatabaseModel.ENV_PREFIX, "DB");
        links.save(link);

        handler = new EnvEchoHandler(siteId, Map.of("minimum_processes", 1));
        handler.startMinimumServers();

        String expectedUrl = "postgres://appuser:flowsecret@127.0.0.1:" + conn.port() + "/flowdb";
        long deadline = System.currentTimeMillis() + 30_000;
        String log = "";
        while (System.currentTimeMillis() < deadline) {
            var processes = handler.getProcesses();
            if (!processes.isEmpty()) {
                log = processes.get(0).getLogText();
                if (log.contains("DATABASE_URL=")) {
                    break;
                }
            }
            Thread.sleep(200);
        }

        assertThat(log).contains("DATABASE_URL=" + expectedUrl);
        assertThat(log).contains("DB_URL=" + expectedUrl);
        assertThat(log).contains("DB_HOST=127.0.0.1");
        assertThat(log).contains("DB_PORT=" + conn.port());
        assertThat(log).contains("DB_USER=appuser");
        assertThat(log).contains("DB_PASSWORD=flowsecret");
        assertThat(log).contains("DB_NAME=flowdb");
    }
}
