package be.elevenways.hohenheim.test.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.SiteInstances;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The buildpack (nixpacks) lane against a REAL daemon: detection asserted on what the
 * artifact actually RUNS, the sandbox properties re-proven from INSIDE a buildpack
 * build with discriminating probes, digest stability across two identical builds, and
 * the named refusal of an undetectable repository -- nixpacks itself exits 0 on those,
 * so the refusal under test is OURS.
 *
 * AIDEV-NOTE: these builds pull the nixpacks runtime base image from ghcr.io INSIDE the
 * sandbox on every build (kaniko has no cross-build cache here, by design), so this
 * suite is network-heavy and the first run on a host is the slowest.
 */
class NixpacksBuildLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** Owner of the end-to-end nixpacks site journey. */
    private static final int JOURNEY_SITE_ID = 977_201;

    /** Owner of the digest-stability builds; distinct so histories never mix. */
    private static final int STABILITY_SITE_ID = 977_202;

    /** Owner of the undetectable-repository refusal. */
    private static final int REFUSED_SITE_ID = 977_203;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-nixpacks-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        LiveIdOffsets.apply(datasource);
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
     * Detection is REAL and the sandbox holds on this lane, in one journey: a
     * Dockerfile-less node repository, configured on the SITE as builder=nixpacks,
     * builds through the ordinary site convergence and the running artifact answers as
     * a node app -- asserted on what RUNS, not on a detection field. The same build
     * carries the in-build sandbox probes (npm build script = tenant code inside the
     * kaniko build), each with a positive control so the probes discriminate.
     */
    @Test
    void aNodeRepositoryWithoutADockerfileBuildsAndRunsAsANodeApp() {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null, "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();
        String nonce = "n" + System.nanoTime();
        Path context = createNodeContext("journey", nonce, true);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Map<String, Object> settings = Map.of(
                "build_context", context.toString(),
                "builder", BuildOperationModel.KIND_NIXPACKS,
                "container_port", 8080);

            SiteInstances.SiteRuntime runtime =
                SiteInstances.ensureRunning(JOURNEY_SITE_ID, "nixpacks-journey", settings);
            int instanceId = runtime.instanceId();
            String handle = "hohenheim-instance-" + instanceId;
            try {
                // 1. What RUNS answers as a node app: the response carries our nonce AND
                //    the node runtime's own version string -- a repository of one
                //    language built as another could not produce this.
                String answer = httpAnswer(docker, handle);
                assertThat(answer).as("step 1: the running app answers with the nonce")
                    .contains("hello-" + nonce + "-");
                assertThat(answer)
                    .as("step 1: and with a node version (proof node runs it): " + answer)
                    .matches("(?s).*-v\\d+\\.\\d+\\.\\d+.*");
                // The plan's variables must genuinely reach the image build: nixpacks
                // declares them as Dockerfile ARG/ENV pairs, so a dropped --build-arg
                // hand-off would leave NODE_ENV empty in the running app.
                assertThat(answer)
                    .as("step 1: the plan variables reached the build (NODE_ENV): " + answer)
                    .contains("-env=production");

                // 2. The detection RECORD says what the detector saw: exactly the node
                //    provider, the pinned tool version, and the emitted Dockerfile.
                Row build = Models.get(BuildOperationModel.class)
                    .latestSuccess(SiteModel.MODEL_ID.toString(), JOURNEY_SITE_ID);
                assertThat(build).as("step 2: the site's build was recorded").isNotNull();
                assertThat((String) build.get(BuildOperationModel.BUILDER_KIND))
                    .as("step 2: as a nixpacks build")
                    .isEqualTo(BuildOperationModel.KIND_NIXPACKS);
                String detection = build.get(BuildOperationModel.DETECTION);
                assertThat(detection).as("step 2: the detection record exists").isNotNull();
                assertThat(detection).as("step 2: names exactly the node provider")
                    .contains("\"providers\":[\"node\"]");
                assertThat(detection).as("step 2: carries the pinned tool version")
                    .contains("\"tool\":\"nixpacks\"")
                    .contains(String.valueOf(HohenheimSettings.VALUES.getValue(
                        HohenheimSettings.Builds.NIXPACKS_VERSION)));
                assertThat(detection).as("step 2: and the emitted Dockerfile text")
                    .contains("FROM ghcr.io/railwayapp/nixpacks");

                // 3. Sandbox properties INSIDE the buildpack build, with positive
                //    controls: the probes ran as tenant code (npm build script) inside
                //    the kaniko build. Each negative has a control proving the probe CAN
                //    report the other answer, so none of these is a constant.
                String log = build.get(BuildOperationModel.LOG);
                assertThat(log).as("step 3: the daemon socket is absent inside the build")
                    .contains("hh-socket=absent");
                assertThat(log).as("step 3: control -- the socket probe CAN see a socket")
                    .contains("hh-socket-control=present");
                assertThat(log).as("step 3: the daemon is unreachable over the network")
                    .contains("hh-daemon=unreachable");
                assertThat(log).as("step 3: control -- the connect probe CAN reach a port")
                    .contains("hh-daemon-control=reachable");

                // 4. The release is pinned by digest, same contract as the dockerfile
                //    lane -- one artifact path, one pinning discipline.
                assertThat((String) build.get(BuildOperationModel.IMAGE_ID))
                    .as("step 4: the artifact is digest-pinned").startsWith("sha256:");
            } finally {
                try {
                    SiteInstances.destroyFor(JOURNEY_SITE_ID);
                } catch (RuntimeException ignored) {
                    // teardown best effort; the assertions above are the outcome
                }
                removeImage(docker, "hohenheim-site-" + JOURNEY_SITE_ID + ":latest");
                Row build = Models.get(BuildOperationModel.class)
                    .latestSuccess(SiteModel.MODEL_ID.toString(), JOURNEY_SITE_ID);
                if (build != null) {
                    removeImage(docker, build.get(BuildOperationModel.IMAGE_ID));
                }
            }
        });
        deleteRecursively(context);
    }

    /**
     * Digest stability: two builds over two IDENTICAL fresh contexts produce the SAME
     * image identity. Load-bearing for the release engine -- the digest is what a
     * release pins, and an unstable one would roll every site on every reload.
     */
    @Test
    void twoIdenticalContextsBuildToTheSameDigest() {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null, "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();
        String nonce = "stable";
        Path first = createNodeContext("stable-a", nonce, false);
        Path second = createNodeContext("stable-b", nonce, false);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            SandboxedBuilds.Result one = nixpacksBuild(docker, STABILITY_SITE_ID, first);
            SandboxedBuilds.Result two = nixpacksBuild(docker, STABILITY_SITE_ID, second);
            try {
                assertThat(one.succeeded())
                    .as("step 1: the first build succeeded (log tail: "
                        + tail(logOf(one)) + ")").isTrue();
                assertThat(two.succeeded())
                    .as("step 1: the second build succeeded (log tail: "
                        + tail(logOf(two)) + ")").isTrue();
                assertThat(two.imageId())
                    .as("step 2: an identical context builds to the identical digest")
                    .isEqualTo(one.imageId());
            } finally {
                removeImage(docker, one.tag());
                removeImage(docker, two.tag());
                removeImage(docker, one.imageId());
                removeImage(docker, two.imageId());
            }
        });
        deleteRecursively(first);
        deleteRecursively(second);
    }

    /**
     * An undetectable repository is refused BY NAME, before anything is spent: no image
     * build starts, no artifact exists, and the refusal is a recorded status with the
     * detector's empty observation -- never nixpacks' own silent exit 0.
     */
    @Test
    void anUndetectableRepositoryIsRefusedByName() {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null, "no private netns: the sandbox refuses to build unprotected");
        DockerClient docker = new DockerClient();
        Path context = createContextWith("refused", Map.of(
            "README.txt", "nothing buildable lives here\n"));

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            SandboxedBuilds.Result result = nixpacksBuild(docker, REFUSED_SITE_ID, context);
            String log = logOf(result);

            // 1. The refusal is OURS and it is named: nixpacks exits 0 on this input.
            assertThat(result.succeeded()).as("step 1: an undetectable repo is not a success")
                .isFalse();
            assertThat(result.status()).as("step 1: it is REFUSED, not merely failed")
                .isEqualTo(BuildOperationModel.STATUS_REFUSED);
            assertThat(result.failureReason())
                .as("step 1: and the reason names the detector's finding")
                .contains("nixpacks detected no provider");

            // 2. Nothing was spent past detection: the kaniko sandbox never ran (its
            //    start line is printed only by the main build) and no artifact exists.
            assertThat(log).as("step 2: the image build never started")
                .doesNotContain("build started under quota");
            Row row = Models.get(BuildOperationModel.class).findById(result.buildId());
            assertThat((String) row.get(BuildOperationModel.IMAGE_ID))
                .as("step 2: no image was promoted").isNull();
            assertThat(catchThrowable(() -> docker.inspectImage(result.tag())))
                .as("step 2: and the tag exists nowhere at the daemon")
                .isInstanceOfSatisfying(DockerClient.ApiException.class,
                    e -> assertThat(e.isNotFound()).isTrue());

            // 3. What the detector SAW is a recorded column even on refusal, so the
            //    refusal is explainable without log archaeology.
            String detection = row.get(BuildOperationModel.DETECTION);
            assertThat(detection).as("step 3: the detection record exists on refusal")
                .isNotNull();
            assertThat(detection).as("step 3: with the empty provider observation")
                .contains("\"providers\":[]");
        });
        deleteRecursively(context);
    }

    // -- fixtures -------------------------------------------------------------

    private static SandboxedBuilds.Result nixpacksBuild(DockerClient docker, int ownerId,
                                                        Path context) {
        String tag = "hohenheim-buildtest-nixpacks-" + System.nanoTime() + ":latest";
        return new SandboxedBuilds(docker, WorkloadNetworkPolicy.current(), () -> {})
            .run(new BuildRequest(SiteModel.MODEL_ID, ownerId,
                BuildOperationModel.KIND_NIXPACKS, context, null, tag, Map.of(),
                "test-ref", null, new BuildQuota(2.0, 2048, 4096L * 1024 * 1024,
                    600_000, 256, 512 * 1024, 1024L * 1024 * 1024)));
    }

    /**
     * A dependency-free node app: an HTTP server answering the nonce plus the node
     * runtime version. With {@code probes}, the npm {@code build} script runs the
     * sandbox probes as tenant code inside the image build.
     */
    private static Path createNodeContext(String name, String nonce, boolean probes) {
        String scripts = probes
            ? "\"build\": \"node probe.js\", \"start\": \"node index.js\""
            : "\"start\": \"node index.js\"";
        Path context = createContextWith(name, Map.of(
            "package.json", """
                {
                  "name": "hh-nixpacks-app",
                  "version": "1.0.0",
                  "scripts": { %s }
                }
                """.formatted(scripts),
            "index.js", """
                const http = require('http');
                http.createServer((req, res) => {
                  res.end('hello-%s-' + process.version + '-env=' + process.env.NODE_ENV);
                }).listen(8080);
                """.formatted(nonce)));
        if (probes) {
            write(context.resolve("probe.js"), PROBE_JS);
        }
        return context;
    }

    /**
     * The in-build sandbox probes. Every negative claim has a positive control right
     * beside it: the socket probe also inspects a unix socket node itself creates, and
     * the daemon probe also connects to a TCP port node itself listens on -- so
     * "absent"/"unreachable" are discriminating observations, not constants.
     */
    private static final String PROBE_JS = """
        const fs = require('fs');
        const net = require('net');
        function sockState(p) {
          try { return fs.statSync(p).isSocket() ? 'present' : 'notsocket'; }
          catch (e) { return 'absent'; }
        }
        console.log('hh-socket=' + sockState('/var/run/docker.sock'));
        const unix = net.createServer();
        unix.listen('/tmp/hh-probe.sock', () => {
          console.log('hh-socket-control=' + sockState('/tmp/hh-probe.sock'));
          unix.close(probeDaemon);
        });
        function tryConnect(host, port, label, next) {
          let done = false;
          const settle = (verdict) => {
            if (done) return;
            done = true;
            console.log(label + '=' + verdict);
            socket.destroy();
            next();
          };
          const socket = net.connect({ host: host, port: port, timeout: 3000 });
          socket.on('connect', () => settle('reachable'));
          socket.on('timeout', () => settle('unreachable'));
          socket.on('error', () => settle('unreachable'));
        }
        function probeDaemon() {
          tryConnect('172.17.0.1', 2375, 'hh-daemon', () => {
            const tcp = net.createServer();
            tcp.listen(0, '127.0.0.1', () => {
              tryConnect('127.0.0.1', tcp.address().port, 'hh-daemon-control', () => {
                tcp.close(() => process.exit(0));
              });
            });
          });
        }
        """;

    private static Path createContextWith(String name, Map<String, String> files) {
        try {
            Path context = Files.createTempDirectory("hohenheim-nixpacks-live-" + name);
            files.forEach((file, content) -> write(context.resolve(file), content));
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * AIDEV-NOTE: the mtime pin mirrors production, where checkout slots are REUSED so
     * unchanged tenant files keep their timestamps -- kaniko's COPY layers hash mtimes
     * even under --reproducible, so "identical contexts" means content AND mtimes.
     * Without it this test would depend on both temp dirs landing in the same clock
     * second (it did, flakily).
     */
    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fetch the app's answer from INSIDE the running container, retrying while it boots. */
    private static String httpAnswer(DockerClient docker, String handle) {
        String script = "require('http').get('http://127.0.0.1:8080/', res => {"
            + " let d = ''; res.on('data', c => d += c);"
            + " res.on('end', () => { console.log(d); process.exit(0); });"
            + " }).on('error', () => process.exit(1))";
        String last = "";
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                last = docker.exec(handle, List.of("node", "-e", script)).output();
                if (last.contains("hello-")) {
                    return last;
                }
            } catch (IOException retried) {
                last = "(exec failed: " + retried.getMessage() + ")";
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private static String logOf(SandboxedBuilds.Result result) {
        Row row = Models.get(BuildOperationModel.class).findById(result.buildId());
        String log = row == null ? null : row.get(BuildOperationModel.LOG);
        return log == null ? "" : log;
    }

    private static void removeImage(DockerClient docker, String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        try {
            docker.removeImage(reference, true);
        } catch (IOException ignored) {
            // the artifact is this test's own debris
        }
    }

    private static String tail(String log) {
        if (log == null || log.isEmpty()) {
            return "(no log)";
        }
        return log.length() <= 1200 ? log : log.substring(log.length() - 1200);
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
