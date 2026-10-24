package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.TestImages;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Secret;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DEDICATED database's password change against a REAL daemon, one journey per engine and image: the engine's own
 * reply admits the new password and refuses the old one, before and after a restart of its container (which recreates
 * it from the engine instance's stored environment), no container command or process inside the engine spells its
 * password, and a workload attached to the postgres one reads as running behind the password until its own restart
 * gives it the new one.
 *
 * AIDEV-NOTE: every refusal is preceded by the identical probe taken as a POSITIVE ANCHOR from the same container, the
 * SharedDatabaseEngineLiveTest discipline: "refused because the probe is broken" must never pass as "refused". The probe
 * is the product's own credential check ({@code Engine.credentialCheckCommand}), the one a drifted password change is
 * settled with, so this also proves that check admits exactly the password the engine holds.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class DedicatedDatabasePasswordLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String OLD_PASSWORD = "Old.password-00000000001";
    private static final String NEW_PASSWORD = "New.password-00000000002";
    private static final String USER = "appuser";
    private static final String DATABASE = "appdb";

    /** The image each engine is proven on: its own default, and redis 6 too, whose entrypoint rewrites arguments. */
    private static final Map<ManagedDatabase.Engine, String> IMAGES = Map.of(
        ManagedDatabase.Engine.POSTGRES, "postgres:17-alpine",
        ManagedDatabase.Engine.MYSQL, "mysql:8.0",
        ManagedDatabase.Engine.MONGO, "mongo:7",
        ManagedDatabase.Engine.REDIS, "redis:7-alpine");

    /** An older redis an existing installation may still run: its entrypoint inserts arguments before ours. */
    private static final String REDIS_6 = "redis:6-alpine";

    private static SqlDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        netns = PrivateNetns.installEnforcing();
    }

    @AfterAll
    static void tearDown() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    static Stream<Arguments> engines() {
        return Stream.concat(
            Stream.of(ManagedDatabase.Engine.values()).map(engine -> Arguments.of(engine, IMAGES.get(engine))),
            Stream.of(Arguments.of(ManagedDatabase.Engine.REDIS, REDIS_6)));
    }

    @ParameterizedTest(name = "a dedicated {0} engine on {1} takes its new password and keeps it across a restart")
    @MethodSource("engines")
    void aDedicatedEngineTakesItsNewPasswordAndKeepsItAcrossARestart(ManagedDatabase.Engine engine, String image)
            throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, image);
        boolean withWorkload = engine == ManagedDatabase.Engine.POSTGRES;
        if (withWorkload) {
            LiveLane.requireImage(docker, TestImages.ALPINE);
        }
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
        Db.run(datasource, () -> {
            try {
                journey(docker, engine, image, withWorkload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void journey(DockerClient docker, ManagedDatabase.Engine engine, String image, boolean withWorkload)
            throws Exception {
        HostFixtures.makeLocalPlaceable(16384);
        DatabaseService service = new DatabaseService(datasource);
        String name = "ownpw" + engine.token() + System.nanoTime();
        Integer workloadId = null;
        try {
            // 1. A dedicated, volume-backed database answering its own client with the password it was created with.
            service.create(name, engine, image, USER, OLD_PASSWORD, DATABASE, false,
                ServerService.LOCAL_HOST_NAME, ResourceLimits.none(), DatabaseModel.PLACEMENT_DEDICATED);
            DatabaseModel databases = Models.get(DatabaseModel.class);
            int databaseId = databases.findByName(name).get(DatabaseModel.ID);
            String handle = EngineHandles.of(name);
            assertAdmits(docker, engine, handle, OLD_PASSWORD, "step 1 anchor");
            assertNoPasswordInCommands(docker, engine, handle, OLD_PASSWORD, "step 1");
            if (withWorkload) {
                workloadId = workload("ownpw-workload" + System.nanoTime());
                Row link = Models.get(InstanceDatabaseModel.class).createEmptyRow();
                link.set(InstanceDatabaseModel.INSTANCE_ID, workloadId);
                link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
                link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
                Models.get(InstanceDatabaseModel.class).save(link);
                new InstanceService().deploy(workloadId);
                assertThat(InstanceDatabaseLinks.staleCredentials(List.of(workloadId)))
                    .as("step 1: the attached workload runs with the current password").isEmpty();
            }

            // 2. The change: the engine admits the new password and refuses the old one, the record and the engine
            //    instance's stored environment name the new one.
            DatabaseWrites.changePassword(databases.findByName(name), Secret.of(NEW_PASSWORD));
            Row rotated = databases.findByName(name);
            assertThat((String) rotated.get(DatabaseModel.DB_PASSWORD)).as("step 2: the record holds the new password")
                .isEqualTo(NEW_PASSWORD);
            assertThat((String) rotated.get(DatabaseModel.FAILURE_REASON)).as("step 2: with no drift stamped")
                .isNull();
            assertAdmits(docker, engine, handle, NEW_PASSWORD, "step 2");
            assertRefuses(docker, engine, handle, OLD_PASSWORD, "step 2");
            int engineInstance = DatabaseInstances.owned(databaseId).get(InstanceModel.ID);
            assertThat(new InstanceVariables().valuesFor(engineInstance))
                .as("step 2: the stored environment is what a deploy writes from the record now")
                .isEqualTo(DatabaseInstances.engineEnvironment(EngineHost.dedicated(rotated)));
            if (withWorkload) {
                assertThat(InstanceDatabaseLinks.staleCredentials(List.of(workloadId)))
                    .as("step 2: the attached workload runs behind the change").containsOnlyKeys(workloadId);
            }

            // 3. A restart of the engine instance recreates the container from its stored environment and the data
            //    volume: the new password still works, the old one is still refused.
            OwnedInstances.inScope(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, databaseId, () -> {
                new InstanceService().restart(engineInstance);
                return null;
            });
            ManagedDatabase.awaitReady(docker, handle, engine, USER, NEW_PASSWORD, DATABASE, 120_000);
            assertAdmits(docker, engine, handle, NEW_PASSWORD, "step 3");
            assertRefuses(docker, engine, handle, OLD_PASSWORD, "step 3");
            assertNoPasswordInCommands(docker, engine, handle, NEW_PASSWORD, "step 3");

            // 4. The attached workload's own restart derives the new password: it no longer runs behind it, and the
            //    environment it was started with carries it.
            if (withWorkload) {
                new InstanceService().restart(workloadId);
                assertThat(InstanceDatabaseLinks.staleCredentials(List.of(workloadId)))
                    .as("step 4: the restarted workload runs with the new password").isEmpty();
                String workloadHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, workloadId);
                assertThat(containerEnv(docker, workloadHandle).get("DB_PASSWORD"))
                    .as("step 4: its environment carries it").isEqualTo(NEW_PASSWORD);
            }
        } finally {
            if (workloadId != null) {
                try {
                    new InstanceService().destroy(workloadId);
                } catch (RuntimeException ignored) {
                    // best effort; the assertions are the outcome
                }
            }
            try {
                service.destroy(name, true);
            } catch (Exception refused) {
                try {
                    service.forceDestroyRecord(name);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Asserts neither the container's command (docker inspect's Cmd, Path and Args) nor any process inside it spells
     * {@code password}: redis's used to, through {@code --requirepass}. Redis's server also still runs as its own
     * user and no config file is left in the container's /tmp.
     */
    private static void assertNoPasswordInCommands(DockerClient docker, ManagedDatabase.Engine engine, String handle,
                                                   String password, String step) throws IOException {
        Map<String, Object> inspect = docker.inspectContainer(handle);
        Object command = inspect.get("Config") instanceof Map<?, ?> config ? config.get("Cmd") : null;
        if (engine.containerCommand() != null) {
            assertThat(command).as("%s (%s) anchor: the container runs the engine's own command", step, engine)
                .isEqualTo(engine.containerCommand());
        }
        assertThat(command + " " + inspect.get("Path") + " " + inspect.get("Args"))
            .as("%s (%s): docker inspect shows no password in the container's command", step, engine)
            .doesNotContain(password);
        DockerClient.ExecResult processes = docker.exec(handle, List.of("sh", "-c",
            "for f in /proc/[0-9]*/cmdline; do tr '\\0' ' ' < \"$f\"; echo; done"));
        String server = switch (engine) {
            case POSTGRES -> "postgres";
            case MYSQL -> "mysqld";
            case MONGO -> "mongod";
            case REDIS -> "redis-server";
        };
        assertThat(processes.stdout()).as("%s (%s) anchor: the process table was read", step, engine)
            .contains(server);
        assertThat(processes.stdout()).as("%s (%s): no process inside the container spells the password", step,
            engine).doesNotContain(password);
        if (engine == ManagedDatabase.Engine.REDIS) {
            DockerClient.ExecResult pidOne = docker.exec(handle, List.of("sh", "-c",
                "tr '\\0' ' ' < /proc/1/cmdline; echo; stat -c %u /proc/1; ls -A /tmp"));
            List<String> lines = pidOne.stdout().lines().toList();
            assertThat(lines).as("%s (%s) anchor: the container's first process was read: %s", step, engine,
                pidOne.stderr()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(lines.get(0)).as("%s (%s): the container's first process is the server itself", step, engine)
                .startsWith("redis-server");
            assertThat(lines.get(1)).as("%s (%s): which dropped to its own user", step, engine).isNotEqualTo("0");
            assertThat(lines.subList(2, lines.size())).as("%s (%s): and no config file is left in /tmp", step, engine)
                .noneMatch(entry -> entry.startsWith("tmp."));
        }
    }

    /** Asserts the engine's own client, dialled as a consumer would, admits {@code password}. */
    private static void assertAdmits(DockerClient docker, ManagedDatabase.Engine engine, String handle,
                                     String password, String step) throws IOException {
        for (String user : usersOf(engine)) {
            DockerClient.ExecResult answer = probe(docker, engine, handle, user, password);
            assertThat(answer.exitCode()).as("%s (%s, %s): admitted: %s %s", step, engine, user, answer.stdout(),
                answer.stderr()).isZero();
            assertThat(answer.stdout()).as("%s (%s, %s): and answered", step, engine, user)
                .contains(engine == ManagedDatabase.Engine.REDIS ? "PONG" : "1");
        }
    }

    /** Asserts the engine's own client refuses {@code password}, by authentication. */
    private static void assertRefuses(DockerClient docker, ManagedDatabase.Engine engine, String handle,
                                      String password, String step) throws IOException {
        for (String user : usersOf(engine)) {
            DockerClient.ExecResult answer = probe(docker, engine, handle, user, password);
            String said = answer.stdout() + answer.stderr();
            assertThat(answer.exitCode()).as("%s (%s, %s): refused: %s", step, engine, user, said).isNotZero();
            if (engine == ManagedDatabase.Engine.REDIS) {
                assertThat(said).as("%s (%s): refused by authentication", step, engine).doesNotContain("PONG")
                    .containsAnyOf("WRONGPASS", "NOAUTH");
                continue;
            }
            assertThat(said).as("%s (%s, %s): by authentication", step, engine, user).containsAnyOf(
                "password authentication failed", "Access denied", "Authentication failed");
        }
    }

    /** Mysql gave root and the record's user the one password; every other engine has one credential. */
    private static List<String> usersOf(ManagedDatabase.Engine engine) {
        return engine == ManagedDatabase.Engine.MYSQL ? List.of(USER, "root") : List.of(USER);
    }

    /** The product's credential check of {@code user} with {@code password}, as a drifted change is settled with. */
    private static DockerClient.ExecResult probe(DockerClient docker, ManagedDatabase.Engine engine, String handle,
                                                 String user, String password) throws IOException {
        return docker.exec(handle, engine.credentialCheckCommand(user, DATABASE, false),
            engine.credentialCheckEnv(password));
    }

    private static int workload(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", TestImages.ALPINE);
        settings.put("command", "sleep 600");
        settings.put("container_port", 8080);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Map<String, String> containerEnv(DockerClient docker, String handle) throws IOException {
        Map<String, Object> inspect = docker.inspectContainer(handle);
        Map<String, String> env = new LinkedHashMap<>();
        if (inspect.get("Config") instanceof Map<?, ?> config && config.get("Env") instanceof List<?> entries) {
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int eq = text.indexOf('=');
                if (eq > 0) {
                    env.put(text.substring(0, eq), text.substring(eq + 1));
                }
            }
        }
        return env;
    }
}
