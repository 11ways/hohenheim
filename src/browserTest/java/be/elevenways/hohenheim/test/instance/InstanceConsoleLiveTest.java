package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.InstanceNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5's console matchers against a REAL daemon, driven end to end through the hub:
 * alpine's {@code /bin/sh} on an OpenStdin container is the scriptable game-server
 * stand-in -- console commands ARE shell lines, so readiness output, graceful stop and
 * crashes are all produced on demand. Every status assertion reads the DATABASE row
 * (the fenced outcome), and every "container" assertion reads the DAEMON.
 */
class InstanceConsoleLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-console-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Unique per-class instance ids => unique daemon handles (no cross-class 409s).
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        InstanceConsoles.overrideTimingsForTest(null, null);
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    private static void assumeLiveDaemon() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOCKET),
            "Docker socket not present");
        org.junit.jupiter.api.Assumptions.assumeTrue(alpinePresent(),
            "alpine:latest not present locally");
        org.junit.jupiter.api.Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    private static int templateRecord(String name, String readinessLine, String stopCommand) {
        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, name);
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.READINESS_LINE, readinessLine);
        template.set(InstanceTemplateModel.STOP_COMMAND, stopCommand);
        Models.get(InstanceTemplateModel.class).save(template);
        return template.get(InstanceTemplateModel.ID);
    }

    private static int instanceRecord(String name, int templateId, String crashPolicy) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        // No command: alpine's default CMD is /bin/sh, which reads console lines from
        // the OpenStdin stdin the runtime now opens -- the scriptable stand-in.
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.TEMPLATE_ID, templateId);
        row.set(InstanceModel.CRASH_POLICY, crashPolicy);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static String status(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    private static boolean await(long timeoutMs, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Boolean.TRUE.equals(condition.get());
    }

    private static boolean containerRunning(DockerClient docker, String handle) {
        try {
            Object state = docker.inspectContainer(handle).get("State");
            return state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String containerId(DockerClient docker, String handle) {
        try {
            Object id = docker.inspectContainer(handle).get("Id");
            return id == null ? "" : id.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static Integer containerExitCode(DockerClient docker, String handle) {
        try {
            Object state = docker.inspectContainer(handle).get("State");
            return state instanceof Map<?, ?> s && s.get("ExitCode") instanceof Number n
                ? n.intValue() : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    void readinessGracefulStopCrashRestartAndFlapJourney() {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            InstanceConsoles.overrideTimingsForTest(60_000L, null);
            int templateId = templateRecord("console-journey", "SERVER READY", "exit");
            int id = instanceRecord("console-journey", templateId, InstanceModel.CRASH_RESTART);
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            try {
                // 1. Deploy: the readiness matcher is armed, so the record says STARTING
                //    -- NOT Running -- while the daemon says the container runs.
                service.deploy(id);
                assertThat(status(id))
                    .as("step 1: with a readiness line the deploy stamps starting, not running")
                    .isEqualTo(InstanceModel.STATUS_STARTING);
                assertThat(containerRunning(docker, handle))
                    .as("step 1: while the container itself is already up").isTrue();

                // 2. The line has not appeared, so the record STAYS starting.
                assertThat(await(2_000, () -> !InstanceModel.STATUS_STARTING.equals(status(id))))
                    .as("step 2: no readiness line, no Running").isFalse();

                // 3. Make the workload print its readiness line: the matcher flips the
                //    record to RUNNING while the container is still alive -- output
                //    observed DURING the run, which a single-shot transport cannot do.
                InstanceConsoles.sendCommand(id, "echo SERVER READY");
                assertThat(await(15_000, () -> InstanceModel.STATUS_RUNNING.equals(status(id))))
                    .as("step 3: the observed readiness line flips starting -> running").isTrue();
                assertThat(containerRunning(docker, handle))
                    .as("step 3: and the container is still running at the flip").isTrue();

                // 4. Graceful stop: the template's stop command ("exit") rides stdin, the
                //    shell exits 0 (SIGKILL would be 137), and the OBSERVED stop
                //    suppresses crash detection -- despite crash policy RESTART, nothing
                //    restarts this container.
                service.stop(id);
                assertThat(status(id))
                    .as("step 4: an operator stop stamps stopped").isEqualTo(InstanceModel.STATUS_STOPPED);
                assertThat(containerExitCode(docker, handle))
                    .as("step 4: the workload exited by ITS OWN stop command (exit 0),"
                        + " not the daemon's kill").isZero();
                assertThat(await(4_000, () -> containerRunning(docker, handle)))
                    .as("step 4: the observed stop suppressed the restart policy").isFalse();
                assertThat(status(id))
                    .as("step 4: and the record still says stopped")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);

                // 5. Crash: redeploy, reach Running, then die WITHOUT the stop command
                //    ("exit 7" is not "exit"). The watcher treats it as a crash and
                //    redeploys -- the daemon shows a NEW container id with no operator
                //    action (the id change is what proves a real replacement, not the
                //    old container lingering).
                service.deploy(id);
                InstanceConsoles.sendCommand(id, "echo SERVER READY");
                assertThat(await(15_000, () -> InstanceModel.STATUS_RUNNING.equals(status(id))))
                    .as("step 5: ready again after redeploy").isTrue();
                String beforeCrash = containerId(docker, handle);
                InstanceConsoles.sendCommand(id, "exit 7");
                assertThat(await(25_000, () -> !beforeCrash.equals(containerId(docker, handle))
                        && containerRunning(docker, handle)))
                    .as("step 5: an unobserved exit was crash-redeployed into a NEW container")
                    .isTrue();

                // 6. Flap protection: two more rapid crashes hit the threshold (3 inside
                //    the window); the watcher gives up, stamps ERROR and stops restarting.
                assertThat(await(10_000, () -> InstanceConsoles.peek(id) != null
                        && containerRunning(docker, handle)))
                    .as("step 6: the replacement runs and has a console session").isTrue();
                String beforeSecond = containerId(docker, handle);
                InstanceConsoles.sendCommand(id, "exit 7");
                assertThat(await(25_000, () -> !beforeSecond.equals(containerId(docker, handle))
                        && containerRunning(docker, handle)))
                    .as("step 6: crash two still restarts").isTrue();
                assertThat(await(10_000, () -> InstanceConsoles.peek(id) != null
                        && containerRunning(docker, handle)))
                    .as("step 6: and runs with a console session again").isTrue();
                String beforeThird = containerId(docker, handle);
                InstanceConsoles.sendCommand(id, "exit 7");
                assertThat(await(20_000, () -> InstanceModel.STATUS_ERROR.equals(status(id))))
                    .as("step 6: crash three trips flap protection and stamps error").isTrue();
                assertThat(await(4_000, () -> containerRunning(docker, handle)))
                    .as("step 6: and nothing restarts it any more").isFalse();
                assertThat(containerId(docker, handle))
                    .as("step 6: the crashed container was not replaced")
                    .isEqualTo(beforeThird);

                // 7. Destroy tears the console down with the workload.
                service.destroy(id);
                assertThat(InstanceConsoles.peek(id))
                    .as("step 7: no console session survives destroy").isNull();
                assertThat(await(5_000, () -> InstanceConsoles.liveSessionCount() == 0))
                    .as("step 7: the hub holds zero live sessions (counted)").isTrue();
            } finally {
                cleanup(docker, handle);
            }
        });
    }

    @Test
    void anInstanceWhoseReadinessLineNeverAppearsDoesNotReachRunning() {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            // Short readiness deadline so the negative half is observable in-test.
            InstanceConsoles.overrideTimingsForTest(3_000L, null);
            int templateId = templateRecord("never-ready", "THIS LINE NEVER APPEARS", null);
            int id = instanceRecord("never-ready", templateId, InstanceModel.CRASH_NONE);
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            try {
                // 1. Deploy: starting, container up, line never printed.
                service.deploy(id);
                assertThat(status(id))
                    .as("step 1: starting while the line is unseen")
                    .isEqualTo(InstanceModel.STATUS_STARTING);

                // 2. The record NEVER reaches Running; the timeout stamps error.
                assertThat(await(10_000, () -> InstanceModel.STATUS_ERROR.equals(status(id))))
                    .as("step 2: an unobserved readiness line times out into error").isTrue();
                assertThat(status(id))
                    .as("step 2: and Running was never stamped")
                    .isNotEqualTo(InstanceModel.STATUS_RUNNING);
            } finally {
                InstanceConsoles.overrideTimingsForTest(null, null);
                try {
                    service.destroy(id);
                } catch (RuntimeException ignored) {
                    // best-effort teardown; the docker cleanup below is the authority
                }
                cleanup(docker, handle);
            }
        });
    }

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(InstanceNetworks.networkName(handle));
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static boolean alpinePresent() {
        try {
            for (Object image : new DockerClient().listImages()) {
                Object tags = ((Map<?, ?>) image).get("RepoTags");
                if (tags instanceof List<?> list && list.contains("alpine:latest")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
