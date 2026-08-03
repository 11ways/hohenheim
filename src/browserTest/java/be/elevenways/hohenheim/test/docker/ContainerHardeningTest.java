package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.stack.StackDeployer;
import be.elevenways.hohenheim.server.stack.StackSpec;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 */
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

    /** CAP_NET_RAW (bit 13): in Docker's default set, in NO hohenheim profile. */
    private static final long NET_RAW = 1L << 13;

    /** Key {@link #kernelStatusOf} files the pids cgroup cap under. */
    private static final String PIDS_MAX = "PidsMax";

    @BeforeAll
    static void bootRuntime() {
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * THE fifth-authority guard: all four container authorities are walked in one journey
     * and every one of them must land the baseline on a really-running container. A new
     * authority cannot skip this -- {@code DockerClient.createContainer} has no overload
     * without a profile -- but this test is what proves the four that exist do not lie.
     */
    @Test
    void everyContainerAuthorityShipsTheHardeningBaselineOnTheRunningContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");
        int pids = ContainerHardening.pidsLimit();

        // 1. INSTANCE TIER -- declares SERVICE like the other three, because generic
        //    tenant images have the same chown-then-drop shape (see
        //    instanceTierRunsAChownThenDropPrivilegesImage for the workload proof).
        int instanceId = 999_101;
        InstanceSpec spec = new DockerContainerKind().specFor(instanceId, Map.of(
            "image", "alpine", "tag", "latest", "command", "sleep 600"));
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker);
        String handle = runtime.create(spec);
        try {
            runtime.start(handle);
            assertKernelState(docker, handle, "step 1: instance tier", SERVICE_CAPS, pids);
        } finally {
            runtime.destroy(handle);
        }

        // 2. DOCKER SITE -- declares SERVICE (web-server images chown and drop privileges).
        int siteId = 999_102;
        String siteContainer = "hohenheim-site-" + siteId;
        DockerSiteRequestHandler site = new DockerSiteRequestHandler(siteId, Map.of(
            "image", "alpine", "tag", "latest", "container_port", 8080, "command", "sleep 600"));
        try {
            assertKernelState(docker, siteContainer, "step 2: docker site", SERVICE_CAPS, pids);
        } finally {
            site.destroy();
        }

        // 3. MANAGED DATABASE -- declares SERVICE per engine; redis is the fast one to
        //    provision, and it genuinely cannot start without those capabilities.
        ManagedDatabase databases = new ManagedDatabase(docker);
        String dbName = "hardening" + System.nanoTime();
        try {
            databases.provision(dbName, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "appuser", "secret123", "appdb", true, ResourceLimits.none(), 999_103);
            assertKernelState(docker, "hohenheim-db-" + dbName, "step 3: managed database",
                SERVICE_CAPS, pids);
        } finally {
            databases.destroy(dbName, true);
        }

        // 4. STACKS -- operator-authored, declares SERVICE at the tier.
        StackSpec stack = new StackSpec(0, "hhhard" + Long.toHexString(System.nanoTime()),
            "local", null, false, null, null, null,
            StackSpec.topologicallySorted(List.of(new StackSpec.ServiceSpec("app", TEST_IMAGE,
                List.of("sleep", "600"), Map.of(), List.of(), List.of(), List.of(), List.of(),
                null, 1, 3, 3, 0, "no", null, null))));
        StackDeployer deployer = new StackDeployer(docker, null);
        try {
            deployer.deploy(stack);
            assertKernelState(docker, StackDeployer.containerName(stack, "app"),
                "step 4: stack service", SERVICE_CAPS, pids);
        } finally {
            deployer.destroy(stack, true);
        }
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, POSTGRES_IMAGE), POSTGRES_IMAGE + " not present locally");

        int instanceId = 999_104;
        InstanceSpec spec = new DockerContainerKind().specFor(instanceId, Map.of(
            "image", "postgres", "tag", "17-alpine",
            "environment_variables", Map.of("POSTGRES_PASSWORD", "hardening-probe"),
            "volumes", Map.of("data", "/var/lib/postgresql/data")));
        String volume = "hohenheim-instance-" + instanceId + "-vol-data";
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker);
        String handle = runtime.create(spec);
        try {
            // 1. It STARTS: the root entrypoint chowned a freshly created, root-owned
            //    named volume and switched user, which is exactly what STRICT forbids.
            runtime.start(handle);
            waitForLog(docker, handle, "database system is ready to accept connections",
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

            // 5. RESTART: the narrower sets that pass step 1 die here.
            docker.restartContainer(handle, 10);
            waitForLog(docker, handle, "database system is ready to accept connections",
                "step 5: it survives a restart onto the already-chowned volume");
            assertThat(kernelStatusOf(docker, handle).get("Uid").split("\\s+")[0])
                .as("step 5: still unprivileged after the restart").isEqualTo("70");
        } finally {
            runtime.destroy(handle);
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // best effort: the volume only exists on a run that got past create
            }
        }
    }

    /**
     * The refusals are a property of the FUNNEL, not of a profile: the instance tier's
     * widened profile buys a caller nothing structural. Same escapes, asserted with the
     * exact profile the instance tier now declares.
     */
    @Test
    void theInstanceProfileStillRefusesEveryStructuralEscape() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

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

        // 2. Host namespaces, every one of them.
        int index = 0;
        for (String key : List.of("PidMode", "IpcMode", "UTSMode", "NetworkMode", "CgroupnsMode")) {
            assertRefused(docker, profile, "hh-inst-ns" + index++,
                Map.of(key, "host"), "shares a host namespace");
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
     * The pids cap is EXERCISED, not merely present: a container that tries to fork far
     * past its limit is stopped by the kernel, and the failure is visible in its own log.
     */
    @Test
    void pidsLimitStopsAForkStormInsideTheContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

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
        assertThat(result.exitCode()).as("the kernel probe ran inside " + container).isEqualTo(0);
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
        return kernel;
    }

    /**
     * Wait for a line in the container's own log, failing with the FULL log the moment the
     * container exits -- that log is the whole point on a hardening regression.
     */
    private static void waitForLog(DockerClient docker, String container, String needle,
                                   String step) throws IOException {
        for (int attempt = 0; attempt < 120; attempt++) {
            String logs = docker.containerLogs(container, true, true, 200);
            if (logs.contains(needle)) {
                return;
            }
            Object state = docker.inspectContainer(container).get("State");
            if (state instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("Running"))) {
                throw new AssertionError(step + ": the container EXITED before logging \""
                    + needle + "\". Its own log says:\n" + logs);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(step + ": never logged \"" + needle + "\" within 60s:\n"
            + docker.containerLogs(container, true, true, 200));
    }

    private static void assertRefused(DockerClient docker, ContainerHardening.Profile profile,
                                      String prefix, Map<String, Object> hostConfig,
                                      String expected) throws IOException {
        String name = prefix + "-" + System.nanoTime();
        Map<String, Object> spec = Map.of("Image", TEST_IMAGE, "Cmd", List.of("sleep", "30"),
            "HostConfig", hostConfig);
        assertThatThrownBy(() -> docker.createContainer(name, spec, profile))
            .as("the escape is refused, naming what was refused")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUSED")
            .hasMessageContaining(expected);
        try {
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

    private static boolean imagePresent(DockerClient docker, String image) throws IOException {
        for (Object entry : docker.listImages()) {
            Object tags = ((Map<?, ?>) entry).get("RepoTags");
            if (tags instanceof List<?> list && list.contains(image)) {
                return true;
            }
        }
        return false;
    }
}
