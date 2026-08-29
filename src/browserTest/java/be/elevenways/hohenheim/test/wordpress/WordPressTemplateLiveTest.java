package be.elevenways.hohenheim.test.wordpress;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.wordpress.WordPressPhp;
import be.elevenways.hohenheim.server.wordpress.WordPressTemplateSeeder;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WordPress template against a REAL daemon, as one journey: create from the seeded
 * PHP 8.1 template, watch the declared MySQL database provision, deploy, and prove from
 * outside (the installer answers on the published port) and from inside (the injected
 * {@code WORDPRESS_DB_*} family, the table prefix and the proxy fix in wp-config.php)
 * that the workload found its database over the link network.
 */
@Tag("slow") // live lane: needs a real daemon and two real images; runs with the widening flag
class WordPressTemplateLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String WORDPRESS_IMAGE = WordPressPhp.IMAGE + ":" + WordPressPhp.PHP_8_1.tag();
    private static final String MYSQL_IMAGE = "mysql:8.0";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-wordpress-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // One database per class: the controller identity resolves through the CURRENT
        // datasource and the provisioning pool thread reads the record on its own.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    @Test
    void aFreshWordPressFindsItsDeclaredDatabase() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, WORDPRESS_IMAGE);
        LiveLane.requireImage(docker, MYSQL_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Seeds.run(datasource, new WordPressTemplateSeeder());
            InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
            Row template = templates.find()
                .where(InstanceTemplateModel.NAME.eq(
                    WordPressTemplateSeeder.templateName(WordPressPhp.PHP_8_1)))
                .first();
            template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            templates.save(template);

            // 1. Create from the template: the instance row AND its declared database.
            int id = new InstanceTemplates().createFromTemplate(
                templates.findById(template.get(InstanceTemplateModel.ID)),
                "wordpress-live", null, Map.of(), null);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            List<Row> links = Models.get(InstanceDatabaseModel.class).findByInstanceId(id);
            assertThat(links).as("step 1: the declared database is attached").hasSize(1);
            int databaseId = links.get(0).get(InstanceDatabaseModel.DATABASE_ID);
            Row database = Models.get(DatabaseModel.class).findById(databaseId);
            String databaseName = database.get(DatabaseModel.NAME);
            InstanceService service = new InstanceService();
            DatabaseService databases = new DatabaseService();

            try {
                // 2. The database provisions in the background; wait for ACTIVE (or a
                //    named failure) -- the image pull is the slow part.
                String status = awaitDatabase(databaseId, Duration.ofMinutes(4));
                assertThat(status).as("step 2: the declared database provisioned: %s",
                        Models.get(DatabaseModel.class).findById(databaseId)
                            .get(DatabaseModel.FAILURE_REASON))
                    .isEqualTo(DatabaseModel.STATUS_ACTIVE);
                String engineHandle = DatabaseInstances.handleOf(databaseId);
                assertThat(engineHandle).as("step 2: the engine owns an instance").isNotNull();

                // 3. Deploy: the workload comes up on a published loopback port.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state()).as("step 3: running").isEqualTo(ContainerState.RUNNING);
                Integer port = deployed.publishedPort();
                assertThat(port).as("step 3: Apache's port is published").isNotNull();

                // 4. From INSIDE: the injected family names the engine's handle on the
                //    link network, the prefix variable rode along, wp-config carries the
                //    proxy fix once the entrypoint has written it.
                assertThat(exec(docker, handle, "printenv", "WORDPRESS_DB_HOST"))
                    .as("step 4: the image's own DB host variable").isEqualTo(engineHandle);
                assertThat(exec(docker, handle, "printenv", "WORDPRESS_DB_NAME"))
                    .as("step 4: the allocated database name")
                    .isEqualTo(database.get(DatabaseModel.DB_NAME));
                assertThat(exec(docker, handle, "printenv", "WORDPRESS_TABLE_PREFIX"))
                    .as("step 4: the table prefix default").isEqualTo("wp_");

                // 5. From OUTSIDE: a fresh docroot with a reachable database answers the
                //    installer redirect; an unreachable one is a 500 ("Error establishing
                //    a database connection"), which is exactly what this step refuses.
                HttpResponse<String> front = awaitFront(port, Duration.ofSeconds(90));
                assertThat(front.statusCode())
                    .as("step 5: the front page answers below 500: %s", front.body())
                    .isLessThan(500);
                assertThat(front.headers().firstValue("Location").orElse("") + front.body())
                    .as("step 5: and it is WordPress's installer")
                    .contains("wp-admin/install.php");
                assertThat(exec(docker, handle, "grep", "-c", "HTTP_X_FORWARDED_PROTO",
                        WordPressTemplateSeeder.DOCROOT + "/wp-config.php"))
                    .as("step 5: the proxy fix reached wp-config.php").isEqualTo("1");

                service.stop(id);
            } finally {
                cleanup(docker, service, id, handle, databases, databaseName);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static String awaitDatabase(int databaseId, Duration limit) {
        Instant deadline = Instant.now().plus(limit);
        while (true) {
            Row row = Models.get(DatabaseModel.class).findById(databaseId);
            String status = row == null ? null : row.get(DatabaseModel.STATUS);
            if (DatabaseModel.STATUS_ACTIVE.equals(status) || DatabaseModel.STATUS_FAILED.equals(status)
                    || Instant.now().isAfter(deadline)) {
                return status;
            }
            pause(2000);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private static HttpResponse<String> awaitFront(int port, Duration limit) {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/"))
            .timeout(Duration.ofSeconds(20))
            .GET().build();
        Instant deadline = Instant.now().plus(limit);
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                last = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (last.statusCode() < 500) {
                    return last;
                }
            } catch (IOException notYet) {
                // Apache still starting, or the entrypoint still copying WordPress in.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
            pause(3000);
        }
        if (last == null) {
            throw new AssertionError("the front page never answered on port " + port);
        }
        return last;
    }

    private static String exec(DockerClient docker, String container, String... command) {
        try {
            return docker.exec(container, List.of(command)).output().trim();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void cleanup(DockerClient docker, InstanceService service, int instanceId,
                                String handle, DatabaseService databases, String databaseName) {
        try {
            service.destroy(instanceId);
        } catch (RuntimeException ignored) {
            // never deployed, or already gone
        }
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(handle));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
        try {
            docker.removeVolume(handle + "-vol-" + WordPressTemplateSeeder.DOCROOT_VOLUME, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            databases.destroy(databaseName, true);
        } catch (IOException | RuntimeException ignored) {
            // best effort; the record delete below is what the next run needs
        }
        Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(databaseName)).delete();
    }
}
