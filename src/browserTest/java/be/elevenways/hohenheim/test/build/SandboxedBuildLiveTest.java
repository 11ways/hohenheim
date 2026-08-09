package be.elevenways.hohenheim.test.build;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.build.BuildCredentials;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.BuildSandbox;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.instance.InstanceService;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The sandbox against a REAL daemon: every isolation and quota claim of the builders
 * wave, asserted from INSIDE the running build and at the daemon, never from the spec
 * that was sent.
 *
 * AIDEV-NOTE: these builds fetch a base image from the internet, because that is what a
 * build is and the network policy is deliberately egress-restrictive rather than
 * egress-closed. A machine without outbound access cannot run them, and the assumptions
 * say which fact was missing rather than passing quietly.
 */
class SandboxedBuildLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** Owner of the standalone builds here (a synthetic site id). */
    private static final int SITE_ID = 977_101;

    /** Owner of the end-to-end site journey; distinct so histories never mix. */
    private static final int JOURNEY_SITE_ID = 977_102;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-build-live-test", ".db");
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
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    /**
     * The isolation half: a build cannot see the daemon socket, cannot reach the daemon
     * over the network, and receives build arguments but no runtime environment.
     */
    @Test
    void aBuildCannotReachTheDaemon() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();

            // 1. STRUCTURAL: a build container that asked for the daemon socket cannot be
            //    created at all. This is the naive "build in a container with the socket
            //    mounted" design, refused at the one funnel rather than by convention.
            Throwable bound = catchThrowable(() -> docker.createContainer(
                "hohenheim-build-socket-probe", Map.of(
                    "Image", "alpine:latest",
                    "Cmd", List.of("true"),
                    "HostConfig", Map.of("Mounts", List.of(Map.of(
                        "Type", "bind",
                        "Source", "/var/run/docker.sock",
                        "Target", "/var/run/docker.sock")))),
                ContainerHardening.SERVICE));
            assertThat(bound).as("step 1: a socket bind is structurally refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bind mount")
                .hasMessageContaining("root on the host");

            // 2. OBSERVED FROM INSIDE: a build that probes for the daemon reports it
            //    absent and unreachable. The probe exits 0 either way -- the ASSERTION is
            //    on what it printed, so a reachable daemon fails THIS step instead of the
            //    build failing for some unrelated reason.
            SandboxedBuilds.Result probe = build(docker, SITE_ID, "probe", """
                FROM alpine:latest
                RUN echo "hh-socket=$(test -S /var/run/docker.sock && echo present || echo absent)"
                RUN echo "hh-daemon=$(wget -q -T 3 -O - http://172.17.0.1:2375/version >/dev/null 2>&1 && echo reachable || echo unreachable)"
                """, Map.of(), 300_000);
            String log = logOf(probe);

            assertThat(probe.succeeded())
                .as("step 2: the probing build itself succeeded (log tail: " + tail(log) + ")")
                .isTrue();
            assertThat(log).as("step 2: the daemon socket is not visible inside the build")
                .contains("hh-socket=absent");
            assertThat(log).as("step 2: and the daemon is not reachable over the network")
                .contains("hh-daemon=unreachable");

            // 3. A build that TRIES to use the socket FAILS, rather than succeeding
            //    quietly with a step that silently did nothing.
            SandboxedBuilds.Result uses = build(docker, SITE_ID, "uses", """
                FROM alpine:latest
                RUN test -S /var/run/docker.sock
                """, Map.of(), 300_000);
            assertThat(uses.succeeded()).as("step 3: a build that needs the daemon fails")
                .isFalse();
            assertThat(uses.status()).as("step 3: as an ordinary build failure")
                .isEqualTo(BuildOperationModel.STATUS_FAILED);

            // 4. Build ARGUMENTS do reach the build -- without this the environment
            //    assertions in the site journey would be proving that nothing at all is
            //    passed, which is a check that cannot fail.
            String registryPassword = "REGISTRY-LEASE-SECRET-" + System.nanoTime();
            SandboxedBuilds.Result args = buildWithQuota(docker, SITE_ID, "args", """
                FROM alpine:latest
                ARG HH_BUILD_ARG
                RUN echo "hh-arg=$HH_BUILD_ARG"
                RUN echo "hh-registry-config=$(test -f /kaniko/.docker/config.json && echo staged || echo missing)"
                """, Map.of("HH_BUILD_ARG", "reached-the-build"),
                new BuildRequest.RegistryCredential("ghcr.io", "hohenheim-test", registryPassword),
                defaultQuota(300_000));
            String argsLog = logOf(args);
            assertThat(argsLog).as("step 4: a build argument DOES reach the build")
                .contains("hh-arg=reached-the-build");
            assertThat(argsLog).as("step 4: and the leased registry credential was staged")
                .contains("hh-registry-config=staged");

            // 5. Every credential lease of a finished build is gone, on every path. The
            //    build above ISSUED one, so this assertion has something to observe --
            //    a lease count over builds that never leased anything could not fail.
            assertThat(BuildCredentials.leaseCount(args.buildId()))
                .as("step 5: the finished build's credential lease was revoked").isZero();
            assertThat(argsLog).as("step 5: and the credential never came back out in the log")
                .doesNotContain(registryPassword);
            assertThat(argsLog).as("step 5: the redaction is visible, not silent")
                .contains("registry credential leased for ghcr.io");
        });
    }

    /**
     * The quota half: time and disk each bind, each is recorded, and neither leaves a
     * promotable artifact or a running container behind.
     */
    @Test
    void aBuildThatOutgrowsItsQuotaIsKilledAndPromotesNothing() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();

            // 1. TIME: a build that sleeps past its wall-clock quota is killed, the
            //    operation says timed_out, and it carries no image to deploy.
            SandboxedBuilds.Result timedOut = build(docker, SITE_ID, "slow", """
                FROM alpine:latest
                RUN sleep 300
                """, Map.of(), 25_000);
            assertThat(timedOut.succeeded()).as("step 1: a timed-out build is not a success")
                .isFalse();
            assertThat(timedOut.status()).as("step 1: and says why")
                .isEqualTo(BuildOperationModel.STATUS_TIMED_OUT);
            Row timedRow = Models.get(BuildOperationModel.class).findById(timedOut.buildId());
            assertThat((String) timedRow.get(BuildOperationModel.IMAGE_ID))
                .as("step 1: nothing partial is promotable").isNull();
            assertThat((String) timedRow.get(BuildOperationModel.FAILURE_REASON))
                .as("step 1: the reason is on the record").contains("wall-clock quota");

            // 2. HOST STATE: the killed build's container and its private network are
            //    gone from the daemon -- a quota that leaves the workload running is not
            //    a quota.
            String handle = BuildSandbox.handleOf(timedOut.buildId());
            assertThat(catchThrowable(() -> docker.inspectContainer(handle)))
                .as("step 2: the killed build's container was removed")
                .isInstanceOfSatisfying(DockerClient.ApiException.class,
                    e -> assertThat(e.isNotFound()).isTrue());
            assertThat(findNetwork(docker, handle + "-net"))
                .as("step 2: and so was its private network").isNull();

            // 3. DISK: a build that keeps writing crosses its disk quota and is killed by
            //    the watchdog, with the peak the daemon actually reported on the record.
            SandboxedBuilds.Result fat = buildWithQuota(docker, SITE_ID, "fat", """
                FROM alpine:latest
                RUN i=0; while [ $i -lt 400 ]; do dd if=/dev/zero of=/fill.$i bs=1M count=8 2>/dev/null; sleep 0.2; i=$((i+1)); done
                """, Map.of(), new BuildQuota(2.0, 1024, 64L * 1024 * 1024, 240_000, 128,
                    256 * 1024, 512L * 1024 * 1024));
            assertThat(fat.status()).as("step 3: the disk quota bound")
                .isEqualTo(BuildOperationModel.STATUS_QUOTA_EXCEEDED);
            Row fatRow = Models.get(BuildOperationModel.class).findById(fat.buildId());
            assertThat((String) fatRow.get(BuildOperationModel.IMAGE_ID))
                .as("step 3: and promoted nothing").isNull();
            assertThat((Long) fatRow.get(BuildOperationModel.PEAK_DISK_BYTES))
                .as("step 3: the peak is an OBSERVATION off the daemon, not the limit")
                .isGreaterThan(64L * 1024 * 1024);

            // 4. The quota that was IN FORCE is on the record, so a failure stays
            //    explainable without guessing at today's settings.
            assertThat((Integer) fatRow.get(BuildOperationModel.DISK_LIMIT_MB))
                .as("step 4: the disk quota in force was recorded").isEqualTo(64);
            assertThat((Integer) fatRow.get(BuildOperationModel.PIDS_LIMIT))
                .as("step 4: the pid quota in force was recorded").isPositive();
        });
    }

    /**
     * The end-to-end site journey: a git-sourced Docker site builds in the sandbox,
     * deploys the artifact BY DIGEST, keeps its runtime secret out of the build, and
     * keeps running the built artifact when someone moves the tag underneath it.
     */
    @Test
    void aSiteRunsWhatItBuiltAndTheBuildNeverSawItsSecrets() {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();
        String secret = "TENANT-RUNTIME-SECRET-" + System.nanoTime();
        // AIDEV-NOTE: both ARGs are DECLARED, which is the point. A build container's own
        // environment does NOT reach a RUN step (measured: kaniko gives RUN the image's env
        // plus DECLARED build args and nothing else), so dumping `env` inside a RUN would be
        // a check that cannot fail. The channel a runtime secret would ACTUALLY leak through
        // is a build ARG, so the test declares the tenant variable as one and requires it to
        // arrive EMPTY -- while a real build argument beside it must arrive populated.
        Path context = createContext("journey", """
            FROM alpine:latest
            ARG TENANT_DB_PASSWORD
            ARG HH_CONTROL
            RUN echo "hh-tenant=${TENANT_DB_PASSWORD:-EMPTY}"
            RUN echo "hh-control=${HH_CONTROL:-EMPTY}"
            RUN echo built-by-the-sandbox > /hohenheim-marker
            """);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Map<String, Object> settings = Map.of(
                "build_context", context.toString(),
                "dockerfile", "Dockerfile",
                "container_port", 8080,
                "command", "sleep 300",
                "build_arguments", Map.of("HH_CONTROL", "control-value"),
                "environment_variables", Map.of("TENANT_DB_PASSWORD", secret));

            SiteInstances.SiteRuntime runtime =
                SiteInstances.ensureRunning(JOURNEY_SITE_ID, "build-journey", settings);
            int instanceId = runtime.instanceId();
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
            try {
                // 1. The site's release is pinned to a DIGEST, not to the tag the build
                //    also applied -- a tag is a mutable pointer and cannot be an identity.
                Row instance = Models.get(InstanceModel.class).findById(instanceId);
                @SuppressWarnings("unchecked")
                Map<String, Object> stored =
                    (Map<String, Object>) instance.get(InstanceModel.SETTINGS);
                String pinned = String.valueOf(stored.get("image"));
                assertThat(pinned).as("step 1: the release is pinned by digest")
                    .startsWith("sha256:");

                // 2. The DAEMON agrees: the running container's image is that digest, and
                //    the file our Dockerfile wrote is in it.
                Map<String, Object> inspect = inspect(docker, handle);
                assertThat(String.valueOf(inspect.get("Image")))
                    .as("step 2: the daemon runs exactly the pinned artifact").isEqualTo(pinned);
                assertThat(exec(docker, handle, "cat", "/hohenheim-marker"))
                    .as("step 2: and it is the thing the sandbox built")
                    .contains("built-by-the-sandbox");

                // 3. The build never saw the tenant's runtime environment, asserted from
                //    inside the builder; the RUNNING workload does have it, so this is a
                //    separation and not an omission everywhere.
                Row build = Models.get(BuildOperationModel.class)
                    .latestSuccess(SiteModel.MODEL_ID.toString(), JOURNEY_SITE_ID);
                assertThat(build).as("step 3: the site's build was recorded").isNotNull();
                String log = build.get(BuildOperationModel.LOG);
                assertThat(log).as("step 3: a real build argument DOES arrive (the control)")
                    .contains("hh-control=control-value");
                assertThat(log).as("step 3: the tenant's runtime variable arrives EMPTY")
                    .contains("hh-tenant=EMPTY");
                assertThat(log).as("step 3: and its value is nowhere in the build log")
                    .doesNotContain(secret);
                assertThat(String.valueOf(inspectImage(docker, pinned)))
                    .as("step 3: nor anywhere in the artifact's own config or history")
                    .doesNotContain(secret);
                assertThat(exec(docker, handle, "sh", "-c", "echo $TENANT_DB_PASSWORD"))
                    .as("step 3: while the RUNTIME workload does receive it").contains(secret);

                // 4. THE tag counterfactual: move the human-facing tag onto a different
                //    image and redeploy. A tag-pinned release would now run alpine; the
                //    digest-pinned one still runs what was built.
                tag(docker, "alpine:latest", ControllerScope.handle(ControllerScope.KIND_SITE, JOURNEY_SITE_ID), "latest");
                new InstanceService().deploy(instanceId);
                Map<String, Object> after = inspect(docker, handle);
                assertThat(String.valueOf(after.get("Image")))
                    .as("step 4: a moved tag does not move a pinned release").isEqualTo(pinned);
                assertThat(exec(docker, handle, "cat", "/hohenheim-marker"))
                    .as("step 4: and the built artifact is still what runs")
                    .contains("built-by-the-sandbox");
            } finally {
                try {
                    SiteInstances.destroyFor(JOURNEY_SITE_ID);
                } catch (RuntimeException ignored) {
                    // teardown best effort; the assertions above are the outcome
                }
                removeImage(docker, ControllerScope.handle(ControllerScope.KIND_SITE, JOURNEY_SITE_ID) + ":latest");
                Row build = Models.get(BuildOperationModel.class)
                    .latestSuccess(SiteModel.MODEL_ID.toString(), JOURNEY_SITE_ID);
                if (build != null) {
                    removeImage(docker, build.get(BuildOperationModel.IMAGE_ID));
                }
            }
        });
        deleteContext(context);
    }

    // -- fixtures -------------------------------------------------------------

    private static BuildQuota defaultQuota(long timeoutMs) {
        return new BuildQuota(2.0, 1024, 2048L * 1024 * 1024, timeoutMs, 128, 256 * 1024,
            512L * 1024 * 1024);
    }

    private static SandboxedBuilds.Result build(DockerClient docker, int ownerId, String name,
                                                String dockerfile, Map<String, String> args,
                                                long timeoutMs) {
        return buildWithQuota(docker, ownerId, name, dockerfile, args, defaultQuota(timeoutMs));
    }

    private static SandboxedBuilds.Result buildWithQuota(DockerClient docker, int ownerId,
                                                         String name, String dockerfile,
                                                         Map<String, String> args,
                                                         BuildQuota quota) {
        return buildWithQuota(docker, ownerId, name, dockerfile, args, null, quota);
    }

    private static SandboxedBuilds.Result buildWithQuota(DockerClient docker, int ownerId,
                                                         String name, String dockerfile,
                                                         Map<String, String> args,
                                                         BuildRequest.RegistryCredential registry,
                                                         BuildQuota quota) {
        Path context = createContext(name, dockerfile);
        try {
            String tag = "hohenheim-buildtest-" + name + "-" + System.nanoTime() + ":latest";
            SandboxedBuilds.Result result = new SandboxedBuilds(docker,
                WorkloadNetworkPolicy.forServer(ServerModel.MODE_LOCAL), () -> {}).run(new BuildRequest(
                    SiteModel.MODEL_ID, ownerId, BuildOperationModel.KIND_DOCKERFILE,
                    context, "Dockerfile", tag, args, "test-ref", registry, quota));
            removeImage(docker, result.imageId());
            return result;
        } finally {
            deleteContext(context);
        }
    }

    private static Path createContext(String name, String dockerfile) {
        try {
            Path context = Files.createTempDirectory("hohenheim-build-live-" + name);
            Files.writeString(context.resolve("Dockerfile"), dockerfile);
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteContext(Path context) {
        try {
            Files.deleteIfExists(context.resolve("Dockerfile"));
            Files.deleteIfExists(context);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private static void removeImage(DockerClient docker, String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        try {
            docker.removeImage(reference, true);
        } catch (IOException ignored) {
            // the artifact is this test's own debris; a stubborn one never fails an
            // assertion about isolation
        }
    }

    private static String logOf(SandboxedBuilds.Result result) {
        Row row = Models.get(BuildOperationModel.class).findById(result.buildId());
        String log = row == null ? null : row.get(BuildOperationModel.LOG);
        return log == null ? "" : log;
    }

    private static String exec(DockerClient docker, String handle, String... command) {
        try {
            return docker.exec(handle, List.of(command)).output();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> inspectImage(DockerClient docker, String reference) {
        try {
            return docker.inspectImage(reference);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> inspect(DockerClient docker, String handle) {
        try {
            return docker.inspectContainer(handle);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void tag(DockerClient docker, String image, String repo, String tag) {
        try {
            docker.tagImage(image, repo, tag);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> findNetwork(DockerClient docker, String name) {
        try {
            return docker.findNetworkByName(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String tail(String log) {
        if (log == null || log.isEmpty()) {
            return "(no log)";
        }
        return log.length() <= 800 ? log : log.substring(log.length() - 800);
    }
}
