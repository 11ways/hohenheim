package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceShell;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.server.runtime.PtySupport;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The interactive shell against a REAL Docker daemon and a REAL container, because every
 * claim it makes is about what the DAEMON does: that the pseudo-terminal exists at all,
 * that it lands as the workload's own uid and not root, that a resize reaches the tty, and
 * that teardown happens exactly once however the session ends.
 *
 * <p>The gates are proved without a daemon in {@code InstanceShellGateTest}; what only
 * this half can say is that the thing behind the gates works, and works as the right
 * identity. The uid claim is asserted from INSIDE the container ({@code id -u}), never
 * from the spec we handed the daemon -- a spec is an intention, not evidence.</p>
 *
 * AIDEV-NOTE: the workload is a purpose-built test KIND rather than the real workspace
 * kind, because a workspace demands a btrfs volume root and a built runtime image (a
 * remote live host). What matters to this test is the two facts the workspace kind
 * supplies -- a NON-ROOT runUser and a Docker runtime carrying the streaming lane -- and
 * this kind declares exactly those, through the same {@code InstanceSpec.runUser} and the
 * same {@code DockerInstanceRuntime} constructor the workspace uses.
 */
@Tag("slow") // live lane: needs a real Docker daemon; runs via `zenit-dev test --class`
class InstanceShellLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** The non-root uid the workload -- and therefore its shell -- must run as. */
    private static final int RUN_USER = 1000;

    /** How long any single output expectation may wait for the shell to answer. */
    private static final long OUTPUT_TIMEOUT_MS = 20_000;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static long startedAt;

    /**
     * Dump every stack after a stall so a hang NAMES its blocking frame.
     *
     * AIDEV-NOTE: kept, not scaffolding. A live-lane hang costs the whole budget and the
     * harness reports it as "in flight", which says nothing about where. This plus the
     * markers below turn that into evidence. Both are the reason the 2026-08-23
     * ten-minute stall was traced to a probe exec riding DockerClient's 600s cap.
     */
    private static void startStallDump() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("=== SHELL LIVE STALL DUMP ===");
            Thread.getAllStackTraces().forEach((t, frames) -> {
                System.out.println("--- " + t.getName() + " state=" + t.getState());
                for (StackTraceElement frame : frames) {
                    System.out.println("      at " + frame);
                }
            });
            System.out.flush();
        });
        thread.setDaemon(true);
        thread.start();
    }

    /** Timestamped progress marker: a hang must name the step it hung ON. */
    private static void mark(String what) {
        System.out.println("[shell-live +"
            + (System.currentTimeMillis() - startedAt) + "ms] " + what);
        System.out.flush();
    }

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-shell-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        // BEFORE the boot: the grant below is on InstanceModel, and zenit-auth refuses a
        // grant on an undeclared model. Same order ServerMain uses.
        HohenheimTestRuntime.declareAccessModelsOnce();
        HohenheimTestRuntime.ensureBooted();
        LiveShellKind.register();
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

    /**
     * One journey: open, echo, prove the uid, resize, close, close again -- with the
     * activity log asserted at each transition.
     */
    @Test
    void shellJourneyAgainstTheRealDaemonAsTheWorkloadsOwnUid() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        startedAt = System.currentTimeMillis();
        startStallDump();
        String[] handle = new String[1];
        try {
            Db.run(datasource, () -> {
                assertThat(ActivityLog.isInstalled())
                    .as("the activity log must be installed or the audit steps prove nothing")
                    .isTrue();
                mark("admitLocal");
                HostFixtures.admitLocal();

                int id = instanceRecord("shell-live-journey");
                handle[0] = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
                mark("deploy");
                new InstanceService().deploy(id);

                int userId = tenant("shell-live@hohenheim.test");
                RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, id,
                    HohenheimAccess.SHELL, true);
                Principal tenant = new UserPrincipal((long) userId, "Shell Live Tenant");

                StringBuilder output = new StringBuilder();
                AtomicInteger ended = new AtomicInteger();

                // 1. The session opens for a shell delegate against a real container.
                InstanceShell.Session session = new InstanceShell().open(id, tenant,
                    100, 30,
                    text -> {
                        synchronized (output) {
                            output.append(text);
                        }
                    },
                    ended::incrementAndGet);
                mark("step1-opened");
                assertThat(session.isOpen()).as("step 1: the session reports itself open")
                    .isTrue();
                assertThat(InstanceShell.liveSessionCount(id))
                    .as("step 1: and is registered against the instance").isEqualTo(1);

                // 2. It is a real terminal: a command typed into it echoes and answers.
                mark("step2-write");
                session.write("echo hohenheim-shell-probe\n");
                awaitOutput(output, "hohenheim-shell-probe",
                    "step 2: the shell never echoed a command typed into it");

                // 3. THE UID CLAIM, asked of the container itself: the shell runs as the
                //    workload's own uid and NOT as root. This is the gate's whole point,
                //    so it is measured inside the container rather than read off the spec.
                session.write("echo uid=$(id -u):$(id -un 2>/dev/null || echo nomame)\n");
                awaitOutput(output, "uid=" + RUN_USER + ":",
                    "step 3: the shell did not land as the workload's own uid " + RUN_USER);
                assertThat(snapshot(output))
                    .withFailMessage("step 3: the shell reported uid 0 -- a root shell in a"
                        + " container hohenheim cannot user-namespace-remap is root on the"
                        + " host, which is exactly what the uid gate exists to prevent")
                    .doesNotContain("uid=0:");

                // 4. Resize reaches the pseudo-terminal: `stty size` prints rows cols, so
                //    a terminal stuck at its opening geometry fails here.
                mark("step4-resize");
                session.resize(132, 50);
                session.write("stty size\n");
                awaitOutput(output, "50 132",
                    "step 4: a resize never reached the pseudo-terminal (stty size still"
                        + " reports the geometry the session opened with)");

                // 5. Opening the session was audited, attributed to the tenant.
                mark("step5-activity");
                List<Row> opened = activityFor(id, InstanceShell.ACTIVITY_OPEN);
                assertThat(opened)
                    .as("step 5: opening a shell writes EXACTLY ONE activity row")
                    .hasSize(1);
                assertThat((String) opened.get(0).get(ActivityModel.ACTOR))
                    .as("step 5: the row names the acting principal, not the system")
                    .isEqualTo(String.valueOf(userId));
                assertThat((String) opened.get(0).get(ActivityModel.RECORD_ID))
                    .as("step 5: and the instance it was opened on")
                    .isEqualTo(String.valueOf(id));
                assertThat((String) opened.get(0).get(ActivityModel.DETAIL))
                    .as("step 5: and the interpreter and uid it ran as")
                    .contains("as uid " + RUN_USER);

                // 6. Teardown is EXACTLY ONCE: one close, one audit row, one onEnd.
                mark("step6-close");
                session.close(InstanceShell.EndReason.CLIENT);
                assertThat(session.isOpen()).as("step 6: a closed session reports closed")
                    .isFalse();
                assertThat(activityFor(id, InstanceShell.ACTIVITY_CLOSE))
                    .as("step 6: closing writes exactly one activity row")
                    .hasSize(1);
                assertThat(ended.get())
                    .as("step 6: and the end callback ran exactly once")
                    .isEqualTo(1);
                assertThat(InstanceShell.liveSessionCount(id))
                    .as("step 6: and the session left the registry")
                    .isZero();

                // 7. ...and ALWAYS ONCE: a second close is a no-op, not a second audit row,
                //    a second callback, or a throw. Both ends of a socket legitimately call
                //    close (our own teardown and the framework's onClose), so this is the
                //    normal path and not a defensive extra.
                session.close(InstanceShell.EndReason.EXITED);
                session.write("echo after-close\n");
                session.resize(80, 24);
                assertThat(activityFor(id, InstanceShell.ACTIVITY_CLOSE))
                    .withFailMessage("step 7: a second close wrote a SECOND close entry --"
                        + " teardown must be exactly-once whichever end fires it")
                    .hasSize(1);
                assertThat(ended.get())
                    .as("step 7: and did not run the end callback again")
                    .isEqualTo(1);
                assertThat(snapshot(output))
                    .as("step 7: and writes after close reach nothing")
                    .doesNotContain("after-close");

                // 8. The concurrency bound is real: the (max + 1)th session is refused BY
                //    NAME, and the ones that were allowed are still open.
                mark("step8-bound");
                List<InstanceShell.Session> held = new ArrayList<>();
                try {
                    for (int i = 0; i < InstanceShell.MAX_SESSIONS_PER_INSTANCE; i++) {
                        mark("step8-open-" + i);
                        held.add(new InstanceShell().open(id, tenant, 80, 24,
                            text -> { }, () -> { }));
                        mark("step8-opened-" + i);
                    }
                    assertThat(InstanceShell.liveSessionCount(id))
                        .as("step 8: the bound's worth of sessions really opened")
                        .isEqualTo(InstanceShell.MAX_SESSIONS_PER_INSTANCE);
                    Throwable refused = catchThrowable(() -> new InstanceShell().open(
                        id, tenant, 80, 24, text -> { }, () -> { }));
                    assertThat(keyOf(refused))
                        .withFailMessage("step 8: an unbounded number of shells could be"
                            + " opened on one instance from a browser tab (got: "
                            + refused + ")")
                        .isEqualTo("shell_too_many_sessions");
                } finally {
                    for (InstanceShell.Session extra : held) {
                        extra.close(InstanceShell.EndReason.CLIENT);
                    }
                }

                mark("destroy");
                new InstanceService().destroy(id);
            });
        } finally {
            cleanup(docker, handle[0]);
        }
    }

    /**
     * The interpreter is the IMAGE's declaration, never a hardcoded bash -- and an image
     * that declares one it does not ship falls back rather than opening a terminal that
     * dies with an unreadable exit code.
     */
    @Test
    void aDeclaredShellThatTheImageDoesNotShipFallsBackAndIsDetectedAtTheDriver() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        startedAt = System.currentTimeMillis();
        startStallDump();
        String[] handle = new String[1];
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();
                mark("fb-image");
                int imageId = runtimeImageDeclaring("/bin/nosuchshell");
                int id = instanceRecord("shell-live-fallback");
                Row row = Models.get(InstanceModel.class).findById(id);
                row.set(InstanceModel.RUNTIME_IMAGE_ID, imageId);
                Models.get(InstanceModel.class).save(row);
                mark("fb-deploy");
                handle[0] = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
                new InstanceService().deploy(id);

                int userId = tenant("shell-fallback@hohenheim.test");
                RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, id,
                    HohenheimAccess.SHELL, true);
                Principal tenant = new UserPrincipal((long) userId, "Fallback Tenant");

                // 1. COUNTERFACTUAL at the driver: the daemon ACCEPTS an exec for an
                //    interpreter that does not exist (101 + a hijacked stream), so
                //    "the exec was created" proves nothing -- only failedToStart does.
                mark("fb-resolve");
                PtySupport driver = (PtySupport) new InstanceService().resolve(id).runtime();
                InstanceSpec spec = new InstanceService().resolve(id).spec();
                mark("fb-open-bogus");
                PtySupport.PtySession bogus = openPty(driver, spec, "/bin/nosuchshell");
                mark("fb-open-real");
                PtySupport.PtySession real = openPty(driver, spec, "/bin/sh");
                try {
                    mark("fb-settle");
                    settle();
                    assertThat(failedToStart(bogus))
                        .withFailMessage("step 1: a missing interpreter was not detected --"
                            + " the daemon answers 101 for it just like a real shell")
                        .isTrue();
                    assertThat(failedToStart(real))
                        .withFailMessage("step 1: a REAL shell was reported as failed to"
                            + " start -- the detector would refuse every session")
                        .isFalse();
                } finally {
                    bogus.close();
                    real.close();
                }

                // 2. And the surface uses that to FALL BACK: the image declares a shell it
                //    does not ship, so the session opens on /bin/sh instead of refusing.
                mark("fb-open-session");
                StringBuilder output = new StringBuilder();
                InstanceShell.Session session = new InstanceShell().open(id, tenant, 80, 24,
                    text -> {
                        synchronized (output) {
                            output.append(text);
                        }
                    }, () -> { });
                try {
                    assertThat(session.isOpen())
                        .as("step 2: the session opened despite the declared shell missing")
                        .isTrue();
                    List<Row> opened = activityFor(id, InstanceShell.ACTIVITY_OPEN);
                    assertThat(opened).as("step 2: and it was audited").hasSize(1);
                    assertThat((String) opened.get(0).get(ActivityModel.DETAIL))
                        .withFailMessage("step 2: the audit names the shell that was NOT"
                            + " started; the fallback must be the one recorded")
                        .startsWith("/bin/sh ");
                    session.write("echo fallback-alive\n");
                    awaitOutput(output, "fallback-alive",
                        "step 2: the fallback shell is not a working terminal");
                } finally {
                    session.close(InstanceShell.EndReason.CLIENT);
                }

                new InstanceService().destroy(id);
            });
        } finally {
            cleanup(docker, handle[0]);
        }
    }

    // -- plumbing -------------------------------------------------------------

    private static PtySupport.@NonNull PtySession openPty(PtySupport driver, InstanceSpec spec,
                                                          String shell) {
        try {
            return driver.openPty(spec, List.of(shell), 80, 24);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean failedToStart(PtySupport.PtySession session) {
        try {
            return session.failedToStart();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Long enough for the daemon to have reported a failed exec (MEASURED at ~50ms). */
    private static void settle() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A runtime image row whose declared shell is the subject of the test. */
    private static int runtimeImageDeclaring(String shell) {
        Row image = Models.get(RuntimeImageModel.class).createEmptyRow();
        image.set(RuntimeImageModel.NAME, "shell-live-declares-" + shell.replace("/", "-"));
        image.set(RuntimeImageModel.DOCKER_IMAGE, "alpine:latest");
        image.set(RuntimeImageModel.SHELL, shell);
        image.set(RuntimeImageModel.ENABLED, true);
        Models.get(RuntimeImageModel.class).save(image);
        return image.get(RuntimeImageModel.ID);
    }


    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    /** Wait for the shell to produce {@code expected}; fail with the message if it never does. */
    private static void awaitOutput(StringBuilder output, String expected, String failure) {
        long deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (snapshot(output).contains(expected)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(snapshot(output)).withFailMessage(failure + " (looking for '"
            + expected + "'; the terminal produced: " + snapshot(output) + ")")
            .contains(expected);
    }

    private static List<Row> activityFor(int instanceId, String action) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .and(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .and(ActivityModel.ACTION.eq(action))
            .all();
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, LiveShellKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<String, Object>());
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int tenant(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static String keyOf(Throwable thrown) {
        if (!(thrown instanceof Violations violations)) {
            return thrown == null ? "" : thrown.getClass().getSimpleName();
        }
        List<Violation> all = violations.all();
        return all.isEmpty() ? "" : all.get(0).message().key();
    }

    private static void cleanup(DockerClient docker, String container) {
        if (container == null) {
            return;
        }
        try {
            docker.removeContainer(container, true);
        } catch (Exception alreadyGone) {
            // already removed by destroy()
        }
    }

    /**
     * A Docker kind that runs as a NON-ROOT uid and carries the streaming lane -- the two
     * facts the workspace kind supplies, with none of its btrfs/runtime-image ceremony.
     */
    private static final class LiveShellKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "live_shell_workload");
        static final Schema SETTINGS_SCHEMA = new Schema();
        private static boolean registered;

        static void register() {
            if (!registered) {
                registered = true;
                InstanceKinds.register(new LiveShellKind());
            }
        }

        @Override public @NonNull Identifier typeId() { return ID; }

        @Override public @NonNull String getDisplayName() { return "Live shell workload"; }

        @Override public @NonNull Microcopy getLabel() {
            return Microcopy.of("live_shell_workload").withFilter("scope", "instance_kind");
        }

        @Override public @NonNull Microcopy getDescription() {
            return Microcopy.of("live_shell_workload")
                .withFilter("scope", "instance_kind_description");
        }

        @Override public Icon getIcon() { return Icon.of("flask"); }

        @Override public String getColor() { return "gray"; }

        @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override public @NonNull Set<String> supportedRuntimes() {
            return Set.of(be.elevenways.hohenheim.model.ServerModel.RUNTIME_DOCKER);
        }

        @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }

        /** The SAME constructor the workspace kind uses, streaming lane and all. */
        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            ServerService servers = new ServerService();
            return new DockerInstanceRuntime(servers.clientFor(serverName),
                WorkloadNetworkPolicy.forServer(serverName),
                NetworkPosture.PRIVATE, Egress.OPEN, servers.transportFor(serverName));
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
            return InstanceSpec.builder(handle, "alpine:latest",
                    ResourceLimits.none(), ContainerHardening.SERVICE,
                    OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
                .command(List.of("sleep", "3600"))
                .runUser(RUN_USER)
                .build();
        }
    }
}
