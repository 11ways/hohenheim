package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
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
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5's console matchers against a REAL daemon, driven end to end through the hub:
 * alpine's {@code /bin/sh} on an OpenStdin container is the scriptable game-server
 * stand-in -- console commands ARE shell lines, so readiness output, graceful stop and
 * crashes are all produced on demand. Every status assertion reads the DATABASE row
 * (the fenced outcome), and every "container" assertion reads the DAEMON.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
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
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
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
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.requireImage(new DockerClient(), "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    private static int templateRecord(String name, String readinessLine, String stopCommand) {
        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, name);
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        // The line only decides when the KIND says so; the column default is `port`.
        template.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
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
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
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
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
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

    /**
     * The interactive console against a REAL pseudo-terminal: {@code console_kind=tty}
     * creates the container with a TTY, the hub's viewer handle carries keystrokes and
     * geometry to it, and what comes back is what a terminal would show -- the echo of
     * the typed line and a {@code stty size} that reports the viewer's geometry, which is
     * the whole reason a full-screen TUI (Alchemy's Janeway) can run in it.
     */
    @Test
    void anInteractiveConsoleIsARealPseudoTerminalWithKeystrokesAndGeometry() {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int id = ttyInstanceRecord("console-tty");
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();
            try {
                // 1. Deploy: the daemon confirms the pseudo-terminal, and the record runs.
                service.deploy(id);
                assertThat(status(id)).as("step 1: deployed and running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                Object config = docker.inspectContainer(handle).get("Config");
                assertThat(config instanceof Map<?, ?> c && Boolean.TRUE.equals(c.get("Tty")))
                    .as("step 1: the container was created WITH a TTY").isTrue();

                // 2. The hub attaches interactively and a viewer follows the raw stream.
                InstanceConsoles.Viewer viewer = InstanceConsoles.attach(id);
                assertThat(viewer.interactive())
                    .as("step 2: the session knows it is a pseudo-terminal").isTrue();
                StringBuilder seen = new StringBuilder();
                viewer.follow(chunk -> {
                    synchronized (seen) {
                        seen.append(chunk);
                    }
                });

                // 3. The viewer's geometry reaches the terminal: the shell reports it.
                viewer.resize(120, 40);
                viewer.write("stty size\r");
                assertThat(await(15_000, () -> text(seen).contains("40 120")))
                    .as("step 3: `stty size` reports the geometry the viewer set: "
                        + text(seen)).isTrue();

                // 4. Typed input echoes (a terminal echoes; a pipe never does) and runs.
                viewer.write("echo tty-o''k\r");
                assertThat(await(15_000, () -> text(seen).contains("tty-ok")))
                    .as("step 4: the typed command ran and printed: " + text(seen)).isTrue();
                assertThat(text(seen))
                    .as("step 4: and the keystrokes themselves were echoed back")
                    .contains("echo tty-o''k");

                // 5. A second geometry is honoured live, mid-session.
                viewer.resize(80, 24);
                viewer.write("stty size\r");
                assertThat(await(15_000, () -> text(seen).contains("24 80")))
                    .as("step 5: a live resize reaches the running process: " + text(seen))
                    .isTrue();
                viewer.close();

                // 6. Destroy tears it down with the workload.
                service.destroy(id);
                assertThat(await(5_000, () -> InstanceConsoles.liveSessionCount() == 0))
                    .as("step 6: no session survives destroy").isTrue();
            } catch (IOException e) {
                throw new AssertionError("the daemon could not be asked: " + e.getMessage(), e);
            } finally {
                cleanup(docker, handle);
            }
        });
    }

    private static int ttyInstanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        // alpine's /bin/sh on a TTY is an interactive shell: it echoes, prompts and
        // answers `stty size` -- the smallest stand-in for a TUI workload.
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
            "image", "alpine", "tag", "latest", "command", "sh",
            ConsoleKind.SETTING, ConsoleKind.TTY.token())));
        row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static String text(StringBuilder seen) {
        synchronized (seen) {
            return seen.toString();
        }
    }

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(handle));
        } catch (IOException ignored) {
            // already gone
        }
    }
}
