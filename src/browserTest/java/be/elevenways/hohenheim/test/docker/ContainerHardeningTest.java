package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The container hardening baseline against a REAL daemon, asserted from the KERNEL side
 * rather than from the spec we sent.
 *
 * AIDEV-NOTE: every assertion here reads {@code /proc/1/status} and
 * {@code /sys/fs/cgroup/pids.max} INSIDE the running container, because "we put CapDrop
 * in the JSON" proves nothing about a running container -- that is exactly the theater
 * this baseline exists to remove. CapBnd is the capability BOUNDING set of pid 1: an
 * unhardened Docker container has 0xa80425fb there, a STRICT one has 0x0, and a SERVICE
 * one has 0xcb (CHOWN|DAC_OVERRIDE|FOWNER|SETGID|SETUID). Those numbers are the whole
 * test; if Docker's default set ever changes, only UNHARDENED_CAPS moves. All four
 * authorities declare SERVICE since 2026-08-03 (see {@link DockerContainerKind#HARDENING}
 * for why that is an image-shape statement and not a trust statement), so 0xcb is what
 * every running container here must show and 0xa80425fb is what none of them may.
 *
 * <h2>How to run it</h2>
 * <pre>zenit-dev test --class ContainerHardeningTest --no-fail-fast</pre>
 * ONE command, and the verdict is only half of it: a {@code --class} filter disables the
 * slow-tag exclusion so this class really runs, but on a host with no daemon every method
 * ABORTS and the run is still green. The gate is the SKIP COUNT -- {@code zd_test} returns
 * it as data and the LIVE LANE REPORT names each skip and the need behind it. Six methods
 * ran and zero skipped is the pass; anything else did not test this boundary.
 *
 * AIDEV-NOTE: the {@code -Dhohenheim.live.require=...} policy that would turn those skips
 * into FAILURES is not reachable through this lane, measured 2026-08-23. {@code zenit-dev
 * test} rejects any flag outside its own vocabulary, so a {@code -D} cannot be passed, and
 * {@code GRADLE_OPTS} is swallowed by an already-running Gradle daemon -- probed with
 * {@code GRADLE_OPTS=-Dhohenheim.live.require=docker-socket} against LiveLaneTest, whose
 * declared-need step stayed a skip instead of failing. Until zenit-dev forwards the
 * property, read the count; do not believe a green run that ran nothing.
 *
 * <p>It runs against the LOCAL Docker socket by design -- the boundary under test is
 * {@code ContainerHardening} plus a kernel, and both are the same on any Linux, so an ssh
 * hop to a remote host would add a transport this class does not test and a host that gets
 * reinstalled. A remote daemon is a {@code DockerClient} transport concern with its own
 * live classes.
 *
 * <p>The class stays declared non-hermetic in {@code .zenit-dev.json}: a green receipt
 * here describes a DAEMON, not a source tree, so it must never be reused across a run
 * whose host state changed. Non-hermetic means "never reuse the receipt", not "never
 * run" -- the command above is the lane, and {@code zenit-dev test --all} includes it.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class ContainerHardeningTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** The chown-then-drop-privileges image shape the instance tier must run out of the box. */
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";

    /** CapBnd of a container created with no hardening at all -- what we must never see. */
    private static final long UNHARDENED_CAPS = 0xa80425fbL;

    /** CapBnd of {@link ContainerHardening#SERVICE}: CHOWN|DAC_OVERRIDE|FOWNER|SETGID|SETUID. */
    private static final long SERVICE_CAPS = 0xcbL;

    /** Log lines to read back: comfortably more than any boot here writes, so an
     * occurrence COUNT taken before a restart stays comparable after it. */
    private static final int LOG_TAIL = 500;

    /** CAP_NET_RAW (bit 13): in Docker's default set, in NO hohenheim profile. */
    private static final long NET_RAW = 1L << 13;

    /** The postgres line that means the server really came up. */
    private static final String READY_LINE = "database system is ready to accept connections";

    /** Key {@link #kernelStatusOf} files the pids cgroup cap under. */
    private static final String PIDS_MAX = "PidsMax";

    /**
     * Filesystem types whose mounts are never a host path, however their mount ROOT reads
     * -- Docker's masked /proc entries are tmpfs subtrees, not binds of anything on disk.
     */
    private static final Set<String> PSEUDO_FILESYSTEMS = Set.of(
        "proc", "sysfs", "tmpfs", "devpts", "mqueue", "cgroup", "cgroup2", "overlay",
        "shm", "devtmpfs", "securityfs", "debugfs", "tracefs", "bpf", "fusectl",
        "configfs", "pstore", "hugetlbfs", "ramfs", "binfmt_misc", "nsfs");

    // AIDEV-NOTE: the class-wide netns override is for the paths that resolve their
    // applier through WorkloadNetworkPolicy.forServer (the site tier's deploy AND its
    // destroyFor teardown) -- without it every PRIVATE-posture step refuses on a
    // developer machine. Methods that build a runtime by hand keep their own local
    // PrivateNetns; the two coexist (the local one never touches the override).
    private static PrivateNetns classNetns;

    @BeforeAll
    static void bootRuntime() throws IOException {
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            classNetns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(classNetns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (classNetns != null) {
            classNetns.close();
            classNetns = null;
        }
    }

    /**
     * THE fifth-authority guard: all four container authorities are walked in one journey
     * and every one of them must land the baseline on a really-running container. A new
     * authority cannot skip this -- {@code DockerClient.createContainer} has no overload
     * without a profile -- but this test is what proves the four that exist do not lie.
     */
    @Test
    void everyContainerAuthorityShipsTheHardeningBaselineOnTheRunningContainer() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);
        LiveLane.requireImage(docker, REDIS_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "no private netns: the instance tier refuses to"
            + " deploy where its network policy cannot be enforced");
        int pids = ContainerHardening.pidsLimit();

        // 1. INSTANCE TIER -- declares SERVICE like the other three, because generic
        //    tenant images have the same chown-then-drop shape (see
        //    instanceTierRunsAChownThenDropPrivilegesImage for the workload proof).
        int instanceId = 999_101;
        InstanceSpec spec = new DockerContainerKind().specFor(instanceId, Map.of(
            "image", "alpine", "tag", "latest", "command", "sleep 600"));
        PrivateNetns netns = new PrivateNetns();
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker, netns.enforcingPolicy());
        String handle = runtime.create(spec);
        try {
            runtime.start(handle);
            assertKernelState(docker, handle, "step 1: instance tier", SERVICE_CAPS, pids);
        } finally {
            runtime.destroy(handle);
            netns.close();
        }

        // 2. APPLICATION RELEASE -- declares SERVICE (web-server images chown and drop
        //    privileges). Lowered onto the instance contract: the running release is a
        //    release instance, so the kernel state is asserted on the INSTANCE handle and
        //    the teardown is the verified destroyFor.
        int applicationId = application("hardening-app", Map.of(
            "image", "alpine", "tag", "latest", "container_port", 8080,
            "command", "sleep 600"));
        try {
            ApplicationReleases.Release release =
                ApplicationReleases.converge(applicationId, Map.of());
            assertThat(ApplicationReleases.ownedServing(applicationId))
                .as("step 2: the application's release went through the contract")
                .isNotNull();
            assertKernelState(docker, ControllerScope.handle(
                    ControllerScope.KIND_INSTANCE, release.instanceId()),
                "step 2: application release", SERVICE_CAPS, pids);
        } finally {
            ApplicationReleases.destroyFor(applicationId);
        }

        // 3. MANAGED DATABASE -- declares SERVICE per ENGINE (the kind reads
        //    Engine.hardening(), not a tier constant); redis is the fast one to
        //    provision, and it genuinely cannot start without those capabilities.
        //    Lowered onto the instance contract, so the kernel state is asserted on the
        //    INSTANCE handle its owned engine runs under.
        DatabaseService databaseService = new DatabaseService();
        String dbName = "hardening" + System.nanoTime();
        try {
            databaseService.create(dbName, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "appuser", "secret123", "appdb", true);
            String engineHandle = DatabaseInstances.handleOf(
                Models.get(DatabaseModel.class).findByName(dbName).get(DatabaseModel.ID));
            assertThat(engineHandle)
                .as("step 3: the database's engine went through the contract").isNotNull();
            assertKernelState(docker, engineHandle, "step 3: managed database",
                SERVICE_CAPS, pids);
        } finally {
            try {
                databaseService.destroy(dbName, true);
            } catch (IOException ignored) {
                // best effort
            }
        }

        // 4. STACKS -- operator-authored, declares SERVICE at the tier. Since the
        //    Phase 7 lowering a service IS an owned instance, so this deploys from
        //    RECORDS through StackRuntime, exactly like the product surface does.
        StackRuntime stacks = new StackRuntime(docker, Datasources.getDefault());
        int[] stackIds = stackRecords("hhhard" + Long.toHexString(System.nanoTime()),
            Map.of("app", List.of()));
        try {
            stacks.deploy(stackIds[0], "hardening test");
            assertKernelState(docker, stackContainer(stackIds[1]),
                "step 4: stack service", SERVICE_CAPS, pids);
        } finally {
            stacks.destroy(stackIds[0], true);
        }
    }

    /**
     * Stack + service records for the hardening journeys; the returned array is
     * {@code [stackId, serviceId...]} in declaration order.
     */
    /** The application instance whose converge produces the release under test. */
    private static int application(String name, Map<String, Object> settings) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    private static int[] stackRecords(String stackName, Map<String, List<String>> services) {
        int[] ids = new int[services.size() + 1];
        Db.run(Datasources.getDefault(), () -> {
            StackModel stackModel = Models.get(StackModel.class);
            Row stack = stackModel.createEmptyRow();
            stack.set(StackModel.NAME, stackName);
            stack.set(StackModel.ENABLED, true);
            stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
            stackModel.save(stack);
            ids[0] = stack.get(StackModel.ID);

            StackServiceModel serviceModel = Models.get(StackServiceModel.class);
            int index = 1;
            for (Map.Entry<String, List<String>> entry : services.entrySet()) {
                Row service = serviceModel.createEmptyRow();
                service.set(StackServiceModel.STACK_ID, ids[0]);
                service.set(StackServiceModel.NAME, entry.getKey());
                service.set(StackServiceModel.ENABLED, true);
                service.set(StackServiceModel.IMAGE, TEST_IMAGE);
                service.set(StackServiceModel.COMMAND, List.of("sleep", "600"));
                service.set(StackServiceModel.RESTART_POLICY, "no");
                service.set(StackServiceModel.CAPABILITIES, entry.getValue());
                serviceModel.save(service);
                ids[index++] = service.get(StackServiceModel.ID);
            }
        });
        return ids;
    }

    /** THE daemon-side container name of a stack service, through its owned instance. */
    private static String stackContainer(int serviceId) {
        Integer[] instanceId = new Integer[1];
        Db.run(Datasources.getDefault(), () -> {
            Row instance = StackInstances.owned(serviceId);
            instanceId[0] = instance == null ? null : instance.get(InstanceModel.ID);
        });
        assertThat(instanceId[0]).as("service %s owns an instance", serviceId).isNotNull();
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId[0]);
    }

    /**
     * THE workload this tier exists to run: a generic image whose root entrypoint chowns
     * its data volume and then drops to an unprivileged user, started through the real
     * instance kind. postgres:17-alpine is that shape and it is already on this machine;
     * a game-server image is the same shape.
     *
     * AIDEV-NOTE: this is the test the SERVICE widening was made for, so it must fail for
     * the RIGHT reason if the widening is reverted. Counterfactual run 2026-08-03 with
     * DockerContainerKind.HARDENING = STRICT: the container exits immediately and its own
     * log reads "chmod: /var/lib/postgresql/data: Operation not permitted" / "chmod:
     * /var/run/postgresql: Operation not permitted" / "error: failed switching to
     * 'postgres': operation not permitted". Asserting "the container is running" would
     * NOT have caught that, which is why every assertion below is either kernel state of
     * pid 1 or the container's own readiness line. The restart lap is not padding: the
     * hardening AIDEV-NOTE records that a narrower capability set boots ONCE and dies on
     * the second start, so a single-boot test would report success for a broken set.
     */
    @Test
    void instanceTierRunsAChownThenDropPrivilegesImage() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, POSTGRES_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "no private netns: the instance tier refuses to"
            + " deploy where its network policy cannot be enforced");

        int instanceId = 999_104;
        InstanceSpec spec = new DockerContainerKind().specFor(instanceId, Map.of(
            "image", "postgres", "tag", "17-alpine",
            "environment_variables", Map.of("POSTGRES_PASSWORD", "hardening-probe"),
            "volumes", Map.of("data", "/var/lib/postgresql/data")));
        String volume = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId) + "-vol-data";
        PrivateNetns netns = new PrivateNetns();
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker, netns.enforcingPolicy());
        String handle = runtime.create(spec);
        try {
            // 1. It STARTS: the root entrypoint chowned a freshly created, root-owned
            //    named volume and switched user, which is exactly what STRICT forbids.
            runtime.start(handle);
            waitForLog(docker, handle, READY_LINE,
                "step 1: the chown-then-drop entrypoint completed");

            // 2. It really dropped privileges: pid 1 runs as the image's service user,
            //    not as root. A container that failed to switch would have died in 1.
            Map<String, String> kernel = kernelStatusOf(docker, handle);
            assertThat(kernel.get("Uid").split("\\s+")[0])
                .as("step 2: pid 1 dropped to the unprivileged postgres uid").isEqualTo("70");

            // 3. And it is running on the DECLARED set, not Docker's default.
            long capBnd = Long.parseLong(kernel.get("CapBnd"), 16);
            assertThat(capBnd).as("step 3: the declared SERVICE capability set")
                .isEqualTo(SERVICE_CAPS);
            assertThat(capBnd).as("step 3: never the Docker default set")
                .isNotEqualTo(UNHARDENED_CAPS);
            assertThat(capBnd & NET_RAW).as("step 3: NET_RAW is gone (no spoofing on the"
                + " shared bridge), which the Docker default grants").isEqualTo(0L);

            // 4. The floor did not move with the capability set.
            assertKernelState(docker, handle, "step 4: instance floor", SERVICE_CAPS,
                ContainerHardening.pidsLimit());

            // 5. RESTART: the narrower sets that pass step 1 die here. The log KEEPS every
            //    earlier boot's ready line (postgres logs one for its init-phase temporary
            //    server too), so the wait is for one MORE than we have -- waiting for "a"
            //    ready line would pass without the container ever coming back.
            int readyLines = logOccurrences(docker, handle, READY_LINE);
            docker.restartContainer(handle, 10);
            waitForLog(docker, handle, READY_LINE, readyLines + 1,
                "step 5: it survives a restart onto the already-chowned volume");
            assertThat(kernelStatusOf(docker, handle).get("Uid").split("\\s+")[0])
                .as("step 5: still unprivileged after the restart").isEqualTo("70");
        } finally {
            runtime.destroy(handle);
            netns.close();
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // best effort: the volume only exists on a run that got past create
            }
        }
    }

    /**
     * THE tenant-shell gate: exactly ONE host path crosses into a workspace container --
     * its own declared volume directory -- and it is read out of the container's KERNEL
     * mount table, not out of the spec we sent.
     *
     * AIDEV-NOTE: this is the assertion the product is about to depend on. Everything
     * else in this class proves what a container may NOT be created with; this proves
     * what a legitimately-created one can actually REACH, which is the question a tenant
     * with a shell in it is asking. The bind lane ({@code InstanceSpec.binds} ->
     * {@code ContainerHardening.requireVolumeRootSource}) is what WorkspaceKind deploys
     * through in production and had no live coverage at all: the daemon-side proof was a
     * container on daystrom (2026-08-23), not a test.
     *
     * AIDEV-NOTE: /proc/self/mountinfo field 4 is the SUBTREE of the source filesystem a
     * mount exposes, so a host bind is exactly a mount whose field 4 is not "/". That is
     * what makes "no arbitrary host path" checkable rather than assertable by faith: the
     * whole set is enumerated and compared, so a bind nobody predicted FAILS instead of
     * being silently absent from a list of things we thought to look for.
     */
    @Test
    void aWorkspaceContainerReachesItsOwnVolumeAndNoOtherHostPath() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "no private netns: the instance tier refuses to"
            + " deploy where its network policy cannot be enforced");

        int instanceId = 999_107;
        int neighbourId = 999_108;
        Path volumeRoot = Files.createTempDirectory("hohenheim-volume-root");
        String savedRoot = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.VOLUME_ROOT);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Storage.VOLUME_ROOT, volumeRoot.toString());
        PrivateNetns netns = new PrivateNetns();
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker,
            netns.enforcingPolicy());
        String created = null;
        try {
            // The two directories the controller owns: ours, and the neighbour instance's.
            String ourVolume = InstanceVolumes.hostPathFor(instanceId, "home");
            String neighbourVolume = InstanceVolumes.hostPathFor(neighbourId, "home");
            Files.createDirectories(Path.of(ourVolume));
            Files.createDirectories(Path.of(neighbourVolume));
            Files.writeString(Path.of(ourVolume, "marker"), "our-own-volume\n");
            Files.writeString(Path.of(neighbourVolume, "marker"), "another-tenants\n");

            // The WorkspaceKind shape: one bind of this instance's own volume directory.
            created = runtime.create(InstanceSpec.builder(handle, TEST_IMAGE,
                    ResourceLimits.none(), DockerContainerKind.HARDENING,
                    OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
                .command(List.of("sleep", "600"))
                .binds(Map.of(ourVolume, "/home/site"))
                .build());
            runtime.start(created);

            // 1. THE POSITIVE ANCHOR: the declared volume really is there. Without it,
            //    every containment assertion below would pass on a container that mounted
            //    nothing at all.
            DockerClient.ExecResult ours = docker.exec(created,
                List.of("cat", "/home/site/marker"));
            assertThat(ours.output().trim())
                .as("step 1: the workspace reads its own declared volume")
                .isEqualTo("our-own-volume");

            // 2. THE WHOLE host surface, from the kernel: every mount exposing a SUBTREE
            //    of a host filesystem, keyed by mount point. Docker's own three plumbing
            //    files plus our volume, and nothing else -- an extra bind fails here by
            //    name rather than needing somebody to have thought of it.
            Map<String, String> hostMounts = hostBindsOf(docker, created);
            assertThat(hostMounts.keySet())
                .as("step 2: the ONLY host paths inside a workspace container")
                .containsExactlyInAnyOrder("/home/site", "/etc/resolv.conf",
                    "/etc/hostname", "/etc/hosts");
            assertThat(hostMounts.get("/home/site"))
                .as("step 2: and the one that is not Docker plumbing is exactly the"
                    + " declared volume directory")
                .isEqualTo(ourVolume);
            for (Map.Entry<String, String> mount : hostMounts.entrySet()) {
                if (mount.getKey().equals("/home/site")) {
                    continue;
                }
                assertThat(mount.getValue())
                    .as("step 2: %s comes from the daemon's own per-container directory",
                        mount.getKey())
                    .startsWith("/var/lib/docker/containers/");
            }

            // 3. The neighbour instance's volume -- a directory under the SAME volume root
            //    the hardening funnel permits binds from -- never crossed the boundary.
            assertThat(docker.exec(created, List.of("cat", neighbourVolume + "/marker"))
                    .exitCode())
                .as("step 3: another instance's volume directory is not reachable")
                .isNotEqualTo(0);
            assertThat(docker.exec(created, List.of("ls", volumeRoot.toString()))
                    .exitCode())
                .as("step 3: nor is the volume root that contains both")
                .isNotEqualTo(0);

            // 4. The Docker socket, the one bind that would be host root, is absent as a
            //    FILE and not merely refused as a spec key (step 3 of the refusal journey
            //    proves the spec half).
            assertThat(docker.exec(created,
                    List.of("test", "-e", DockerClient.DEFAULT_SOCKET)).exitCode())
                .as("step 4: no docker socket inside the container")
                .isNotEqualTo(0);

            // 5. And a container that mounts a host directory is hardened exactly like one
            //    that does not: the bind buys no capability and no privilege.
            assertKernelState(docker, created, "step 5: bind-mounting workspace",
                SERVICE_CAPS, ContainerHardening.pidsLimit());

            // 6. THE INJECTED BIND, which is what actually proved the hole on 2026-08-23:
            //    the very same spec plus ONE extra mount naming the neighbour's volume
            //    directory passed this funnel with no refusal, and only step 3's
            //    mountinfo reading caught it. It is refused at the funnel now, so a
            //    daemon never sees it -- asserted on the SPEC SHAPE the runtime emits
            //    rather than through runtime.create, whose replace path would remove the
            //    container the steps above are still asserting against.
            String injectedName = "hh-crossbind-" + System.nanoTime();
            Map<String, Object> injected = new LinkedHashMap<>();
            injected.put("Image", TEST_IMAGE);
            injected.put("Labels", OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
            injected.put("Cmd", List.of("sleep", "30"));
            injected.put("HostConfig", Map.of("Mounts", List.of(
                Map.of("Type", "bind", "Source", ourVolume, "Target", "/home/site"),
                Map.of("Type", "bind", "Source", neighbourVolume, "Target", "/mnt/theirs"))));
            assertThatThrownBy(() -> docker.createContainer(injectedName, injected,
                    DockerContainerKind.HARDENING))
                .as("step 6: an extra bind naming ANOTHER instance's volume directory is"
                    + " refused at the funnel, naming both instances")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#" + instanceId)
                .hasMessageContaining("#" + neighbourId);
            assertThat(catchThrowable(() -> docker.inspectContainer(injectedName)))
                .as("step 6: STATE, not just the throw -- the daemon has no such container")
                .isInstanceOf(DockerClient.ApiException.class);
        } finally {
            if (created != null) {
                runtime.destroy(created);
            }
            netns.close();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT, savedRoot);
            deleteTree(volumeRoot);
        }
    }

    /**
     * The refusals are a property of the FUNNEL, not of a profile: the instance tier's
     * widened profile buys a caller nothing structural. Same escapes, asserted with the
     * exact profile the instance tier now declares.
     */
    @Test
    void theInstanceProfileStillRefusesEveryStructuralEscape() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);

        ContainerHardening.Profile profile = DockerContainerKind.HARDENING;
        assertThat(profile.capabilities()).as("step 0: the instance tier declares SERVICE")
            .containsExactly("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID");

        // 1. Every key the policy owns is still refused under the widened profile.
        assertRefused(docker, profile, "hh-inst-privileged",
            Map.of("Privileged", true), "HostConfig.Privileged");
        assertRefused(docker, profile, "hh-inst-capadd",
            Map.of("CapAdd", List.of("SYS_ADMIN")), "HostConfig.CapAdd");
        assertRefused(docker, profile, "hh-inst-capdrop",
            Map.of("CapDrop", List.of()), "HostConfig.CapDrop");
        assertRefused(docker, profile, "hh-inst-secopt",
            Map.of("SecurityOpt", List.of("seccomp=unconfined")), "HostConfig.SecurityOpt");
        assertRefused(docker, profile, "hh-inst-pids",
            Map.of("PidsLimit", 0), "HostConfig.PidsLimit");
        assertRefused(docker, profile, "hh-inst-userns",
            Map.of("UsernsMode", "host"), "HostConfig.UsernsMode");
        assertRefused(docker, profile, "hh-inst-devices",
            Map.of("Devices", List.of()), "HostConfig.Devices");
        assertRefused(docker, profile, "hh-inst-sysctls",
            Map.of("Sysctls", Map.of("kernel.shmmax", "1")), "HostConfig.Sysctls");
        assertRefused(docker, profile, "hh-inst-readonlypaths",
            Map.of("ReadonlyPaths", List.of()), "HostConfig.ReadonlyPaths");
        assertRefused(docker, profile, "hh-inst-maskedpaths",
            Map.of("MaskedPaths", List.of()), "HostConfig.MaskedPaths");
        assertRefused(docker, profile, "hh-inst-binds",
            Map.of("Binds", List.of("/:/host")), "HostConfig.Binds");

        // 2. Host namespaces, every one of them. Only NetworkMode is a PERMITTED key (the
        //    caller names the per-workload network with it), so it is the one that has to
        //    be caught by the VALUE check; the other four are refused by name because the
        //    key gate is an allow-list.
        assertRefused(docker, profile, "hh-inst-ns-net",
            Map.of("NetworkMode", "host"), "shares a host namespace");
        int index = 0;
        for (String key : List.of("PidMode", "IpcMode", "UTSMode", "CgroupnsMode")) {
            assertRefused(docker, profile, "hh-inst-ns" + index++,
                Map.of(key, "host"), "HostConfig." + key);
        }

        // 2b. Joining ANOTHER container's namespace, which is how a workload opts out of
        //     the per-workload network policy with a string instead of a capability.
        assertRefused(docker, profile, "hh-inst-join-net",
            Map.of("NetworkMode", "container:deadbeef"), "joins another container's namespace");
        index = 0;
        for (String key : List.of("PidMode", "IpcMode", "UTSMode", "CgroupnsMode")) {
            assertRefused(docker, profile, "hh-inst-join" + index++,
                Map.of(key, "container:deadbeef"), "HostConfig." + key);
        }

        // 2c. And the keys that used to pass this funnel verbatim because the gate named
        //     dangers instead of permissions: VolumesFrom inherits another container's
        //     mounts, Runtime picks a different OCI runtime, GroupAdd hands out the docker
        //     gid, Ulimits/OomScoreAdj/ShmSize/Tmpfs weaken host-impact bounds.
        for (Map.Entry<String, Object> smuggled : Map.<String, Object>of(
                "VolumesFrom", List.of("other"),
                "Runtime", "runc-unconfined",
                "GroupAdd", List.of("docker"),
                "Ulimits", List.of(Map.of("Name", "nproc", "Soft", 1048576, "Hard", 1048576)),
                "OomScoreAdj", -1000,
                "ShmSize", 68719476736L,
                "Tmpfs", Map.of("/scratch", "size=64g")).entrySet()) {
            assertRefused(docker, profile, "hh-inst-unlisted" + index++,
                Map.of(smuggled.getKey(), smuggled.getValue()),
                "HostConfig." + smuggled.getKey());
        }

        // 3. A host bind mount, the Docker-socket-is-root-on-the-host case.
        assertRefused(docker, profile, "hh-inst-bindmount",
            Map.of("Mounts", List.of(Map.of("Type", "bind",
                "Source", "/var/run/docker.sock", "Target", "/sock"))),
            "not an isolation boundary");

        // 4. The refusal is a property of the FUNNEL, not of the profile: the same spec
        //    is refused under STRICT, so no profile is a way around it.
        assertRefused(docker, ContainerHardening.STRICT, "hh-strict-privileged",
            Map.of("Privileged", true), "HostConfig.Privileged");

        // 5. A NON-host NetworkMode is untouched: stacks legitimately name their network.
        String allowed = "hh-escape-ok-" + System.nanoTime();
        String id = docker.createContainer(allowed, Map.of("Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30"), "HostConfig", Map.of("NetworkMode", "bridge")),
            profile);
        try {
            assertThat(id).as("step 5: a named network is not an escape").isNotBlank();
        } finally {
            docker.removeContainer(allowed, true);
        }
    }

    /**
     * PER-SERVICE capability declaration, asserted from the KERNEL: a stack service that
     * declares one capability gets exactly that one more than its sibling, and a service
     * that reaches for an escape never reaches the daemon at all.
     *
     * AIDEV-NOTE: the sibling in the SAME stack is the negative anchor and it is
     * load-bearing. A declaration mechanism that quietly widened every service would still
     * pass a test that only looked at the declaring one, and "the whole tier got NET_RAW"
     * is precisely the failure this allow-list exists to prevent. The refusal half then
     * needs its own positive anchor, which step 1 and 2 already are: without them a policy
     * that refused EVERYTHING would look identical.
     */
    @Test
    void aStackServiceDeclaresOneCapabilityAndNeverAnEscape() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "no private netns: a stack refuses to deploy"
            + " where its network policy cannot be enforced");
        int pids = ContainerHardening.pidsLimit();

        String stackName = "hhcap" + Long.toHexString(System.nanoTime());
        StackRuntime stacks = new StackRuntime(docker, Datasources.getDefault());
        // Lowercase and CAP_-prefixed on purpose: compose-shaped content spells
        // capabilities both ways and the funnel normalizes rather than refusing.
        Map<String, List<String>> declared = new LinkedHashMap<>();
        declared.put("plain", List.of());
        declared.put("raw", List.of("cap_net_raw"));
        int[] ids = stackRecords(stackName, declared);
        try {
            stacks.deploy(ids[0], "capability test");

            // 1. THE NEGATIVE ANCHOR: the undeclaring sibling is still exactly SERVICE.
            assertKernelState(docker, stackContainer(ids[1]),
                "step 1: undeclaring sibling", SERVICE_CAPS, pids);

            // 2. THE POSITIVE ANCHOR: the declaring service has SERVICE plus NET_RAW and
            //    nothing else, read out of pid 1's bounding set inside the container.
            assertKernelState(docker, stackContainer(ids[2]),
                "step 2: declaring service", SERVICE_CAPS | NET_RAW, pids);

            // 3. Everything the declaration may NOT move is still in place on the
            //    declaring container: drop-ALL, no-new-privileges, never privileged.
            Map<?, ?> hostConfig = hostConfigOf(docker, stackContainer(ids[2]));
            assertThat(hostConfig.get("CapDrop"))
                .as("step 3: a declaration never weakens the drop-ALL base")
                .isEqualTo(List.of("ALL"));
            assertThat(String.valueOf(hostConfig.get("SecurityOpt")))
                .as("step 3: no-new-privileges survives a declaration")
                .contains("no-new-privileges");
        } finally {
            stacks.destroy(ids[0], true);
        }

        // 4. THE REFUSAL. Every capability the brief names as a container escape is
        //    refused BY NAME, and so is an unknown string -- an allow-list, not a
        //    deny-list, so a capability nobody thought about is refused too.
        for (String escape : List.of("SYS_ADMIN", "SYS_PTRACE", "DAC_READ_SEARCH",
                "SYS_MODULE", "NET_ADMIN", "MKNOD", "SETFCAP", "CAP_SYS_RAWIO",
                "TOTALLY_MADE_UP")) {
            String refusedStack = "hhcapno" + Long.toHexString(System.nanoTime());
            int[] refusedIds = stackRecords(refusedStack, Map.of("app", List.of(escape)));
            Throwable thrown = catchThrowable(() -> stacks.deploy(refusedIds[0], "refusal"));
            try {
                // The product lane wraps every deploy failure as IOException (it is what
                // the deployment log and the admin surface read); the CONTENT is the
                // hardening funnel's own named refusal, which is the assertion that
                // matters and the one that would notice a silently-accepted capability.
                assertThat(thrown)
                    .withFailMessage("step 4: declaring %s deployed instead of being"
                        + " refused", escape)
                    .isInstanceOf(IOException.class);
                assertThat(thrown.getMessage())
                    .as("step 4: the refusal names the capability")
                    .contains("REFUSED capability")
                    .contains(ContainerHardening.normalizeCapability(escape));

                // 4b. And the daemon never got one: scoped to THIS service's own
                //     container name, never a daemon-wide count (four forks share this
                //     daemon).
                assertThat(containerExists(docker, stackContainer(refusedIds[1])))
                    .withFailMessage("step 4: a container was created for a refused"
                        + " capability declaration (%s)", escape)
                    .isFalse();
            } finally {
                // The teardown must survive a FAILING assertion: the counterfactual run
                // that proves this refusal real DOES create the container, and without
                // this it stays on the daemon for the next run to inherit (observed).
                stacks.destroy(refusedIds[0], true);
            }
        }
    }

    /**
     * Every mount inside the container that exposes a SUBTREE of a host filesystem, as
     * {@code container mount point -> host source path}.
     *
     * AIDEV-NOTE: read from {@code /proc/self/mountinfo} rather than from the daemon's
     * {@code Mounts} array, because the daemon's array is a record of what it was ASKED
     * for -- it cannot show a mount the runtime added, and the question here is what the
     * process can reach. Field 4 (the mount ROOT) is the subtree of the source filesystem:
     * "/" for a whole filesystem (proc, sysfs, the overlay rootfs, every tmpfs), an
     * absolute host path for a bind. Kernel pseudo-filesystems are excluded by fs TYPE,
     * so a bind hidden under /proc or /dev would still be listed.
     */
    private static Map<String, String> hostBindsOf(DockerClient docker, String container)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(container,
            List.of("cat", "/proc/self/mountinfo"));
        assertThat(result.exitCode())
            .withFailMessage("could not read the mount table inside %s: %s",
                container, result.output())
            .isEqualTo(0);
        Map<String, String> binds = new LinkedHashMap<>();
        for (String line : result.output().split("\\R")) {
            int separator = line.indexOf(" - ");
            if (separator < 0) {
                continue;
            }
            String[] head = line.substring(0, separator).trim().split("\\s+");
            String[] tail = line.substring(separator + 3).trim().split("\\s+");
            if (head.length < 5 || tail.length < 1) {
                continue;
            }
            String mountRoot = head[3];
            String mountPoint = head[4];
            String type = tail[0];
            if (mountRoot.equals("/") || PSEUDO_FILESYSTEMS.contains(type)) {
                continue;
            }
            binds.put(mountPoint, mountRoot);
        }
        return binds;
    }

    /** Remove a directory tree; the temporary volume root this class mints is all it sees. */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Whether ONE named container exists on the daemon right now. */
    private static boolean containerExists(DockerClient docker, String name) throws IOException {
        try {
            return docker.inspectContainer(name) != null;
        } catch (IOException absent) {
            return false;
        }
    }

    /**
     * The pids cap is EXERCISED, not merely present: a container that tries to fork far
     * past its limit is stopped by the kernel, and the failure is visible in its own log.
     */
    @Test
    void pidsLimitStopsAForkStormInsideTheContainer() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);

        Integer original = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_PIDS_LIMIT);
        String name = "hh-forkstorm-" + System.nanoTime();
        try {
            // 1. A deliberately tiny cap, so the storm is bounded and fast.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.CONTAINER_PIDS_LIMIT, 24);
            assertThat(ContainerHardening.pidsLimit())
                .as("step 1: the setting reaches the policy").isEqualTo(24);

            // 2. Fork 200 children from a container capped at 24 processes.
            docker.createContainer(name, Map.of("Image", TEST_IMAGE, "Cmd", List.of("sh", "-c",
                "i=0; while [ $i -lt 200 ]; do sleep 30 & i=$((i+1)); done; echo NEVER_REACHED")),
                ContainerHardening.STRICT);
            docker.startContainer(name);
            waitForExit(docker, name);

            // 3. The kernel refused the forks; the container never reached its target.
            String logs = docker.containerLogs(name, true, true, 50);
            assertThat(logs).as("step 3: the fork storm is refused by the kernel")
                .contains("can't fork");
            assertThat(logs).as("step 3: the loop never completed").doesNotContain("NEVER_REACHED");

            // 4. And the cap the daemon actually holds is the configured one.
            assertThat(((Number) hostConfigOf(docker, name).get("PidsLimit")).longValue())
                .as("step 4: the daemon holds the configured cap")
                .isEqualTo(24L);
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.CONTAINER_PIDS_LIMIT,
                original != null ? original : ContainerHardening.DEFAULT_PIDS_LIMIT);
            try {
                docker.removeContainer(name, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Assert the baseline from inside the RUNNING container plus the daemon's own record
     * of it.
     *
     * @param expectedCaps the capability bounding set pid 1 must have, as a bitmask
     */
    private static void assertKernelState(DockerClient docker, String container, String step,
                                          long expectedCaps, int expectedPids) throws IOException {
        Map<String, String> kernel = kernelStatusOf(docker, container);
        String pidsMax = kernel.get(PIDS_MAX);

        long capBnd = Long.parseLong(kernel.get("CapBnd"), 16);
        assertThat(capBnd).as(step + ": capability bounding set of pid 1").isEqualTo(expectedCaps);
        assertThat(capBnd).as(step + ": never the Docker default set").isNotEqualTo(UNHARDENED_CAPS);
        assertThat(kernel.get("NoNewPrivs")).as(step + ": no_new_privs is set on pid 1")
            .isEqualTo("1");
        assertThat(pidsMax).as(step + ": the pids cgroup really carries the cap")
            .isEqualTo(String.valueOf(expectedPids));

        Map<?, ?> hostConfig = hostConfigOf(docker, container);
        assertThat(hostConfig.get("CapDrop")).as(step + ": the daemon dropped ALL")
            .isEqualTo(List.of("ALL"));
        assertThat(String.valueOf(hostConfig.get("SecurityOpt")))
            .as(step + ": the daemon holds no-new-privileges").contains("no-new-privileges");
        assertThat(hostConfig.get("Privileged")).as(step + ": never privileged")
            .isEqualTo(Boolean.FALSE);
    }

    /**
     * Read pid 1's {@code /proc/1/status} plus the pids cgroup cap from INSIDE the
     * container, keyed by status field name with the cgroup value under {@link #PIDS_MAX}.
     */
    private static Map<String, String> kernelStatusOf(DockerClient docker, String container)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(container, List.of("sh", "-c",
            "grep -E '^(Uid|CapBnd|NoNewPrivs):' /proc/1/status; cat /sys/fs/cgroup/pids.max"));
        assertThat(result.exitCode())
            .withFailMessage("the kernel probe failed inside %s: exit=%d stdout=%s stderr=%s",
                container, result.exitCode(), result.stdout(), result.stderr())
            .isEqualTo(0);
        Map<String, String> kernel = new LinkedHashMap<>();
        for (String line : result.output().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains(":")) {
                String[] parts = trimmed.split("[:\\s]+", 2);
                kernel.put(parts[0], parts.length > 1 ? parts[1].trim() : "");
            } else {
                kernel.put(PIDS_MAX, trimmed);   // the cgroup file's single value line
            }
        }
        // The probe is `grep ...; cat ...`, so the exit code above is only cat's: a
        // /proc/1/status missing the grepped fields would still exit 0 and the caller
        // would then die on a NumberFormatException instead of a named failure.
        assertThat(kernel)
            .withFailMessage("the kernel probe inside %s answered incompletely:"
                + " stdout=%s stderr=%s", container, result.stdout(), result.stderr())
            .containsKeys("CapBnd", "NoNewPrivs", PIDS_MAX);
        return kernel;
    }

    private static void waitForLog(DockerClient docker, String container, String needle,
                                   String step) throws IOException {
        waitForLog(docker, container, needle, 1, step);
    }

    /** How often the container's log carries {@code needle} right now. */
    private static int logOccurrences(DockerClient docker, String container, String needle)
            throws IOException {
        return occurrences(docker.containerLogs(container, true, true, LOG_TAIL), needle);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Wait until the container's own log carries {@code needle} at least {@code times},
     * failing with the FULL log the moment the container exits -- that log is the whole
     * point on a hardening regression, and the OCCURRENCE COUNT is what makes a wait after
     * a restart mean anything (the log keeps the previous boot's lines).
     */
    private static void waitForLog(DockerClient docker, String container, String needle,
                                   int times, String step) throws IOException {
        for (int attempt = 0; attempt < 120; attempt++) {
            String logs = docker.containerLogs(container, true, true, LOG_TAIL);
            if (occurrences(logs, needle) >= times) {
                return;
            }
            Object state = docker.inspectContainer(container).get("State");
            if (state instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("Running"))) {
                throw new AssertionError(step + ": the container EXITED before logging \""
                    + needle + "\" " + times + " time(s). Its own log says:\n" + logs);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(step + ": never logged \"" + needle + "\" " + times
            + " time(s) within 60s:\n"
            + docker.containerLogs(container, true, true, LOG_TAIL));
    }

    private static void assertRefused(DockerClient docker, ContainerHardening.Profile profile,
                                      String prefix, Map<String, Object> hostConfig,
                                      String expected) throws IOException {
        String name = prefix + "-" + System.nanoTime();
        Map<String, Object> spec = Map.of("Image", TEST_IMAGE, "Cmd", List.of("sleep", "30"),
            "HostConfig", hostConfig);
        // AIDEV-NOTE: the create is INSIDE the try, and that is the whole point of the
        // finally below. It used to sit outside it, so the one run that matters -- a
        // weakened funnel, where createContainer SUCCEEDS and the first assertion fails --
        // left its container on the daemon with nothing to remove it. Measured while
        // falsifying this guard on 2026-08-23: `hh-inst-privileged-<nanos>` survived the
        // run. A cleanup that only runs when there is nothing to clean up is not one.
        try {
            assertThatThrownBy(() -> docker.createContainer(name, spec, profile))
                .as("the escape is refused, naming what was refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFUSED")
                .hasMessageContaining(expected);
            assertThatThrownBy(() -> docker.inspectContainer(name))
                .as("nothing reached the daemon")
                .isInstanceOf(DockerClient.ApiException.class);
        } finally {
            // A FAILING run means a container really was created; do not leave it behind
            // for the next run to trip over.
            try {
                docker.removeContainer(name, true);
            } catch (IOException ignored) {
                // the passing path has nothing to remove
            }
        }
    }

    private static Map<?, ?> hostConfigOf(DockerClient docker, String container) throws IOException {
        return (Map<?, ?>) docker.inspectContainer(container).get("HostConfig");
    }

    private static void waitForExit(DockerClient docker, String container) throws IOException {
        for (int attempt = 0; attempt < 60; attempt++) {
            Object state = docker.inspectContainer(container).get("State");
            if (state instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("Running"))) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("container " + container + " never exited");
    }
}
