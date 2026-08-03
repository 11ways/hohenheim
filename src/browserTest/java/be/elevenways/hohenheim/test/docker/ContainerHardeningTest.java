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
 * one has 0xcb (CHOWN|DAC_OVERRIDE|FOWNER|SETGID|SETUID). Those three numbers are the
 * whole test; if Docker's default set ever changes, only UNHARDENED_CAPS moves.
 */
class ContainerHardeningTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** CapBnd of a container created with no hardening at all -- what we must never see. */
    private static final long UNHARDENED_CAPS = 0xa80425fbL;

    /** CapBnd of {@link ContainerHardening#STRICT}: nothing at all. */
    private static final long STRICT_CAPS = 0L;

    /** CapBnd of {@link ContainerHardening#SERVICE}: CHOWN|DAC_OVERRIDE|FOWNER|SETGID|SETUID. */
    private static final long SERVICE_CAPS = 0xcbL;

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

        // 1. INSTANCE TIER -- the hostile-tenant tier, which declares STRICT.
        int instanceId = 999_101;
        InstanceSpec spec = new DockerContainerKind().specFor(instanceId, Map.of(
            "image", "alpine", "tag", "latest", "command", "sleep 600"));
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker);
        String handle = runtime.create(spec);
        try {
            runtime.start(handle);
            assertKernelState(docker, handle, "step 1: instance tier", STRICT_CAPS, pids);
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
     * The refusals: a spec that carries a privilege escape never reaches the daemon, and
     * the refusal names the key. Each step also asserts that NOTHING was created -- a
     * guard that throws after the container exists would be worse than none.
     */
    @Test
    void privilegeEscapesAreRefusedAndNoContainerIsCreated() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        // 1. Privileged: the whole reason this class exists.
        assertRefused(docker, "hh-escape-privileged",
            Map.of("Image", TEST_IMAGE, "HostConfig", Map.of("Privileged", true)),
            "HostConfig.Privileged");

        // 2. A hand-added capability: capabilities are declared by a profile, never appended.
        assertRefused(docker, "hh-escape-capadd",
            Map.of("Image", TEST_IMAGE, "HostConfig", Map.of("CapAdd", List.of("SYS_ADMIN"))),
            "HostConfig.CapAdd");

        // 3. Opting out of the policy itself (seccomp=unconfined lives here).
        assertRefused(docker, "hh-escape-secopt",
            Map.of("Image", TEST_IMAGE, "HostConfig",
                Map.of("SecurityOpt", List.of("seccomp=unconfined"))),
            "HostConfig.SecurityOpt");

        // 4. Opting a container OUT of daemon userns remapping.
        assertRefused(docker, "hh-escape-userns",
            Map.of("Image", TEST_IMAGE, "HostConfig", Map.of("UsernsMode", "host")),
            "HostConfig.UsernsMode");

        // 5. Sharing a host namespace is a container escape by definition.
        assertRefused(docker, "hh-escape-pidmode",
            Map.of("Image", TEST_IMAGE, "HostConfig", Map.of("PidMode", "host")),
            "shares a host namespace");

        // 6. A bind mount of a host path -- the Docker socket variant is root on the host.
        assertRefused(docker, "hh-escape-bind",
            Map.of("Image", TEST_IMAGE, "HostConfig", Map.of("Mounts", List.of(
                Map.of("Type", "bind", "Source", "/var/run/docker.sock",
                    "Target", "/var/run/docker.sock")))),
            "not an isolation boundary");

        // 7. A NON-host NetworkMode is untouched: stacks legitimately name their network.
        String allowed = "hh-escape-ok-" + System.nanoTime();
        String id = docker.createContainer(allowed, Map.of("Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30"), "HostConfig", Map.of("NetworkMode", "bridge")),
            ContainerHardening.STRICT);
        try {
            assertThat(id).as("step 7: a named network is not an escape").isNotBlank();
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
        DockerClient.ExecResult result = docker.exec(container, List.of("sh", "-c",
            "grep -E '^(CapBnd|NoNewPrivs):' /proc/1/status; cat /sys/fs/cgroup/pids.max"));
        assertThat(result.exitCode()).as(step + ": kernel probe ran").isEqualTo(0);
        Map<String, String> kernel = new LinkedHashMap<>();
        String pidsMax = "";
        for (String line : result.output().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains(":")) {
                String[] parts = trimmed.split("[:\\s]+", 2);
                kernel.put(parts[0], parts.length > 1 ? parts[1].trim() : "");
            } else {
                pidsMax = trimmed;   // the cgroup file's single value line
            }
        }

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

    private static void assertRefused(DockerClient docker, String prefix,
                                      Map<String, Object> spec, String expected) throws IOException {
        String name = prefix + "-" + System.nanoTime();
        assertThatThrownBy(() -> docker.createContainer(name, spec, ContainerHardening.STRICT))
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
