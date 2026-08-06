package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.SiteContainerKind;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Docker-site tier against a real daemon, THROUGH the canonical runtime-resource
 * contract: the handler owns no container -- its running release is a site-owned
 * {@code site_container} instance deployed by InstanceService, and every assertion here
 * pins that (owner labels name the INSTANCE, the ledger claim names the INSTANCE, the
 * attribution columns name the SITE). Skipped when the daemon socket or the test image
 * is absent.
 */
class DockerSiteHandlerTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    private static PrivateNetns netns;

    // The contract writes instance rows and ledger claims, so every start here needs
    // the booted runtime -- a claim that cannot be written is a failure, not a shrug.
    //
    // AIDEV-NOTE: the netns fixture enforces the policy for BOTH halves now. Since the
    // isolation wave a Docker site's running release is PRIVATE (its own policied
    // network) like every other tier, and its BUILD (the tenant's own Dockerfile) runs
    // in the sandbox on a private network too -- both REFUSE when the host cannot enforce
    // a policy. Without this fixture the site would simply be down.
    @BeforeAll
    static void bootRuntime() throws Exception {
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
    @SuppressWarnings("unchecked")
    void startsContainerPublishesPortAndReportsHealth() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        int siteId = 999_001;
        // A long-lived command keeps the container running; the port need not be
        // listened on for Docker to publish the host->container binding. The volume
        // exercises birth-labelling of the one unrecoverable resource kind.
        Map<String, Object> settings = Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600",
            "volumes", Map.of("data", "/data"));
        DockerSiteRequestHandler handler =
            new DockerSiteRequestHandler(siteId, "contract site", settings);

        Integer instanceId = handler.getInstanceId();
        String containerName = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String volumeName = containerName + "-vol-data";
        try {
            // 1. The site's running release IS an owned instance: the row exists, is
            //    attributed to the site, and carries the site_container kind.
            assertThat(instanceId).as("step 1: the handler ensured an owned instance").isNotNull();
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            assertThat((String) instance.get(InstanceModel.GENERATED_BY))
                .as("step 1: attribution source").isEqualTo("site");
            assertThat((String) instance.get(InstanceModel.GENERATED_FOR_MODEL))
                .as("step 1: attribution model").isEqualTo(SiteModel.MODEL_ID.toString());
            assertThat((Integer) instance.get(InstanceModel.GENERATED_FOR_ID))
                .as("step 1: attribution record").isEqualTo(siteId);
            assertThat((String) instance.get(InstanceModel.KIND))
                .isEqualTo(SiteContainerKind.ID.toString());
            assertThat((String) instance.get(InstanceModel.STATUS))
                .as("step 1: the fenced outcome stamp landed")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 2. The product behaviour is unchanged: healthy, proxied over loopback.
            assertThat(handler.getHealth()).as("step 2").isEqualTo(SiteHealth.UP);
            URI upstream = handler.getUpstream();
            assertThat(upstream).isNotNull();
            assertThat(upstream.getScheme()).isEqualTo("http");
            assertThat(upstream.getHost()).isEqualTo("127.0.0.1");
            assertThat(upstream.getPort()).isGreaterThan(0);

            // 3. HOST state: the container runs under the INSTANCE identity -- labels on
            //    the container and on the volume (born labelled, never relabelled).
            Map<String, Object> info = docker.inspectContainer(containerName);
            Map<String, Object> state = (Map<String, Object>) info.get("State");
            assertThat(state.get("Running")).as("step 3").isEqualTo(Boolean.TRUE);
            Map<String, Object> config = (Map<String, Object>) info.get("Config");
            OwnerLabels.Owner containerOwner =
                OwnerLabels.parse((Map<?, ?>) config.get("Labels"));
            assertThat(containerOwner).as("step 3: container owner labels").isNotNull();
            assertThat(containerOwner.model()).isEqualTo(InstanceModel.MODEL_ID);
            assertThat(containerOwner.id()).isEqualTo(String.valueOf(instanceId));
            Map<String, Object> volume = docker.inspectVolume(volumeName);
            OwnerLabels.Owner volumeOwner =
                OwnerLabels.parse((Map<?, ?>) volume.get("Labels"));
            assertThat(volumeOwner).as("step 3: volume owner labels from birth").isNotNull();
            assertThat(volumeOwner.model()).isEqualTo(InstanceModel.MODEL_ID);
            assertThat(volumeOwner.id()).isEqualTo(String.valueOf(instanceId));

            // 4. Record-after: the port the KERNEL picked is in the ledger, owned by the
            //    INSTANCE record -- one authority, visible to every other one.
            String key = PortLedger.claimKeyOf(ServerModel.localServerId(),
                "127.0.0.1", upstream.getPort(), "tcp");
            Row claim = PortLedger.holderOf(key);
            assertThat(claim).as("step 4: the published port is claimed").isNotNull();
            assertThat(PortLedger.isOwnedBy(claim, InstanceModel.MODEL_ID, instanceId))
                .as("step 4: the claim names the owned instance").isTrue();
            assertThatThrownBy(() -> PortLedger.claim(ServerModel.localServerId(), "0.0.0.0",
                    upstream.getPort(), "tcp", null, null, "a rival authority"))
                .as("step 4: another authority cannot double-book the port")
                .isInstanceOf(PortLedger.PortConflict.class);

            // 5. CONVERGENCE: an unchanged handler generation (any routing reload) reuses
            //    the running release instead of restarting it -- same container id -- and
            //    the reuse lane verified ownership, not just the name.
            String runningId = (String) info.get("Id");
            DockerSiteRequestHandler reloaded =
                new DockerSiteRequestHandler(siteId, "contract site", settings);
            assertThat(reloaded.getUpstream()).as("step 5: still up").isNotNull();
            assertThat(reloaded.getUpstream().getPort()).isEqualTo(upstream.getPort());
            assertThat((String) docker.inspectContainer(containerName).get("Id"))
                .as("step 5: the container was reused, not replaced").isEqualTo(runningId);

            // 6. The SUPERSEDED generation's destroy must not stop the workload the
            //    replacing generation answers for (dispatcher order: create new, then
            //    destroy old).
            handler.destroy();
            Map<String, Object> afterOld = docker.inspectContainer(containerName);
            assertThat(((Map<String, Object>) afterOld.get("State")).get("Running"))
                .as("step 6: a superseded generation cannot stop the release")
                .isEqualTo(Boolean.TRUE);

            // 7. The RESPONSIBLE generation's destroy is a route drop: verified STOP,
            //    observed claim release -- the container stays (a disabled site restarts
            //    fast; removal is the site DELETE's verified teardown, step 8).
            reloaded.destroy();
            Map<String, Object> afterStop = docker.inspectContainer(containerName);
            assertThat(((Map<String, Object>) afterStop.get("State")).get("Running"))
                .as("step 7: route drop stops the workload").isEqualTo(Boolean.FALSE);
            assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId))
                .as("step 7: the stop released the claim as observed").isEmpty();
        } finally {
            // 8. End of life (the site-delete path): container removed or observed
            //    absent, instance soft-deleted, claims fully released.
            SiteInstances.destroyFor(siteId);
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
        Row gone = Models.get(InstanceModel.class).findById(instanceId);
        assertThat((Object) gone.get(InstanceModel.DELETED_AT))
            .as("step 8: the owned instance died with the site").isNotNull();
        try {
            docker.inspectContainer(containerName);
            throw new AssertionError("expected inspect of removed container to fail");
        } catch (IOException expected) {
            // 404 from the daemon -> IOException, as intended
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void volumesAndResourceLimitsReachTheContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        int siteId = 999_003;
        DockerSiteRequestHandler handler = new DockerSiteRequestHandler(siteId, Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600",
            "volumes", Map.of("data", "/data"),
            "memory_limit_mb", 128,
            "cpu_limit", 0.5
        ));
        String containerName = ControllerScope.handle(ControllerScope.KIND_INSTANCE, handler.getInstanceId());
        String volumeName = containerName + "-vol-data";
        try {
            Map<String, Object> info = docker.inspectContainer(containerName);
            Map<String, Object> hostConfig = (Map<String, Object>) info.get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue()).isEqualTo(128L * 1024 * 1024);
            assertThat(((Number) hostConfig.get("NanoCpus")).longValue()).isEqualTo(500_000_000L);

            List<?> mounts = (List<?>) info.get("Mounts");
            boolean mounted = mounts.stream().anyMatch(mount ->
                mount instanceof Map<?, ?> m
                    && volumeName.equals(m.get("Name"))
                    && "/data".equals(m.get("Destination")));
            assertThat(mounted).as("named volume mounted at /data").isTrue();
        } finally {
            SiteInstances.destroyFor(siteId);
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    /**
     * The instance tier's collision refusal now guards the site tier: a same-named
     * container the daemon cannot attribute to the site's OWN instance is never
     * force-removed and never reused -- the site goes DOWN (a loud, visible refusal)
     * and the foreign container survives on the host.
     */
    @Test
    void refusesToReplaceOrReuseAForeignSameNamedContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        int siteId = 999_004;
        Map<String, Object> settings = Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600");
        // 1. A first life establishes the owned instance (and its handle), then stops.
        DockerSiteRequestHandler first = new DockerSiteRequestHandler(siteId, settings);
        Integer instanceId = first.getInstanceId();
        assertThat(instanceId).as("step 1").isNotNull();
        String containerName = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        first.destroy();
        try {
            // 2. Remove our stopped container and plant an UNLABELLED squatter on the
            //    instance's handle.
            docker.removeContainer(containerName, true);
            docker.createContainer(containerName, Map.of(
                "Image", "alpine:latest", "Cmd", List.of("sleep", "300")),
                ContainerHardening.STRICT);
            docker.startContainer(containerName);

            // 3. The next generation neither reuses nor replaces it: DOWN, loudly.
            DockerSiteRequestHandler handler =
                new DockerSiteRequestHandler(siteId, settings);
            assertThat(handler.getHealth())
                .as("step 3: the site stays DOWN rather than stealing the name")
                .isEqualTo(SiteHealth.DOWN);
            assertThat(handler.getUpstream())
                .as("step 3: no upstream was resolved").isNull();

            // 4. HOST state: the foreign container survives, unlabelled and unharmed.
            Map<String, Object> info = docker.inspectContainer(containerName);
            assertThat(info).as("step 4: the foreign container still exists").isNotNull();
        } finally {
            try {
                docker.removeContainer(containerName, true);
            } catch (IOException ignored) {
                // best effort
            }
            // The REFUSED deploy in step 3 had already re-created the instance's private
            // network (ensure() runs before the container-collision check), and the hard
            // delete below skips daemon teardown -- without this removal one /16 leaks
            // from Docker's default address pool per run, eventually starving every
            // network-creating test on the machine.
            try {
                docker.removeNetwork(containerName + "-net");
            } catch (IOException ignored) {
                // best effort
            }
            // The instance row remains (its container was squatted, not destroyed);
            // retire it without daemon verification. The hard delete needs the system
            // scope -- outside it the attribution guard refuses ANY write to an owned
            // row, this cleanup included (that refusal is its own test, one class over).
            GeneratedRows.sweeping("site",
                () -> Models.get(InstanceModel.class).delete(instanceId));
        }
    }

    /**
     * A git-provisioned site builds in the SANDBOX from its checkout and the owned
     * instance is pinned to the artifact's DIGEST: an unchanged rebuild converges to the
     * same digest (same container), a changed checkout produces a new digest that rolls
     * the release.
     */
    @Test
    void buildsImageFromContextPinsTheIdAndRollsOnChange() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " (build base) not present");
        assumeTrue(netns != null, "no private netns: the build sandbox refuses to run unprotected");

        int siteId = 999_002;
        String builtImage = ControllerScope.handle(ControllerScope.KIND_SITE, siteId) + ":latest";
        Path context = Files.createTempDirectory("hohenheim-docker-build");
        Integer instanceId = null;
        try {
            Files.writeString(context.resolve("Dockerfile"),
                "FROM alpine:latest\nCMD [\"sleep\", \"3600\"]\n");
            Map<String, Object> settings = Map.of(
                "build_context", context.toString(),
                "container_port", 8080);

            // 1. Build-and-run through the contract, pinned by image ID.
            DockerSiteRequestHandler handler = new DockerSiteRequestHandler(siteId, settings);
            instanceId = handler.getInstanceId();
            assertThat(handler.getUpstream()).as("step 1").isNotNull();
            assertThat(handler.getHealth()).isEqualTo(SiteHealth.UP);
            assertThat(imagePresent(docker, builtImage)).isTrue();
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            Map<?, ?> stored = (Map<?, ?>) instance.get(InstanceModel.SETTINGS);
            assertThat(String.valueOf(stored.get("image")))
                .as("step 1: the release is pinned to the artifact DIGEST, never the tag")
                .startsWith("sha256:")
                .isNotEqualTo(builtImage);
            assertThat(String.valueOf(stored.get("built_image_id")))
                .as("step 1: and the convergence discriminator is the same digest")
                .isEqualTo(String.valueOf(stored.get("image")));

            // 2. An unchanged rebuild converges: the REPRODUCIBLE builder returns the
            //    same digest for the same context, so the settings do not change and the
            //    container is not restarted. Without reproducibility every routing reload
            //    would roll every git-sourced site.
            String containerName = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
            String runningId = (String) docker.inspectContainer(containerName).get("Id");
            String firstDigest = String.valueOf(stored.get("image"));
            new DockerSiteRequestHandler(siteId, settings);
            Map<?, ?> rebuilt = (Map<?, ?>) Models.get(InstanceModel.class)
                .findById(instanceId).get(InstanceModel.SETTINGS);
            assertThat(String.valueOf(rebuilt.get("image")))
                .as("step 2: an identical context builds an identical digest")
                .isEqualTo(firstDigest);
            assertThat((String) docker.inspectContainer(containerName).get("Id"))
                .as("step 2: unchanged checkout, unchanged container").isEqualTo(runningId);

            // 3. A changed checkout builds a NEW image ID and rolls the release THROUGH
            //    THE HEALTH GATE (the release wave): the candidate deploys BESIDE the
            //    serving release as its own instance, must answer HTTP on its published
            //    port, and only then takes over; the superseded release is RETAINED as
            //    the rollback target. The new CMD serves HTTP so the gate can pass.
            // alpine's busybox ships no httpd applet; a forking nc responder is the
            // minimal HTTP listener the probe can pass.
            Files.writeString(context.resolve("answer.sh"),
                "#!/bin/sh\nread line\nprintf 'HTTP/1.1 200 OK\\r\\n"
                    + "Content-Length: 2\\r\\nConnection: close\\r\\n\\r\\nok'\n");
            Files.writeString(context.resolve("Dockerfile"),
                "FROM alpine:latest\nENV RELEASE=2\nCOPY answer.sh /answer.sh\n"
                    + "RUN chmod +x /answer.sh\n"
                    + "CMD [\"nc\", \"-lk\", \"-p\", \"8080\", \"-e\", \"/answer.sh\"]\n");
            DockerSiteRequestHandler rolled = new DockerSiteRequestHandler(siteId, settings);
            assertThat(rolled.getUpstream()).as("step 3: still serving").isNotNull();
            Integer rolledId = rolled.getInstanceId();
            assertThat(rolledId)
                .as("step 3: the new release is a NEW owned instance, not an in-place roll")
                .isNotEqualTo(instanceId);
            Row retained = Models.get(InstanceModel.class).findById(instanceId);
            assertThat((String) retained.get(InstanceModel.RUNTIME_ROLE))
                .as("step 3: the superseded release is retained as the rollback target")
                .isEqualTo(InstanceModel.ROLE_RETIRED);
            assertThat((String) docker.inspectContainer(containerName).get("Id"))
                .as("step 3: the retained release keeps its container")
                .isEqualTo(runningId);
            Map<?, ?> rolledStored = (Map<?, ?>) Models.get(InstanceModel.class)
                .findById(rolledId).get(InstanceModel.SETTINGS);
            assertThat(String.valueOf(rolledStored.get("image")))
                .as("step 3: the new release pins the NEW digest")
                .startsWith("sha256:").isNotEqualTo(firstDigest);
        } finally {
            SiteInstances.destroyFor(siteId);
            try {
                docker.removeImage(builtImage, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            Files.deleteIfExists(context.resolve("Dockerfile"));
            Files.deleteIfExists(context.resolve("answer.sh"));
            Files.deleteIfExists(context);
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
