package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the managed Spamservice lifecycle without loading its nested classes in-process. */
class SpamserviceManagerTest {

    @TempDir Path temp;

    private HttpServer api;
    private int port;
    private final List<String> ensureBodies = new ArrayList<>();
    private volatile String readinessNonce = "wrong";

    @BeforeEach
    void startApi() throws Exception {
        this.api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = this.api.getAddress().getPort();
        this.api.createContext("/v1/clients/ensure", this::ensureClient);
        this.api.createContext("/v1/health/ready", exchange -> {
            exchange.getResponseHeaders().set("X-Spamservice-Launch-Nonce", this.readinessNonce);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        this.api.start();
    }

    @AfterEach
    void stopApi() {
        if (this.api != null) this.api.stop(0);
    }

    @Test
    void extractionIsAtomicHashNamedAndIdempotent() throws Exception {
        byte[] artifact = "nested-spamservice-distribution".getBytes(StandardCharsets.UTF_8);
        SpamserviceManager.ArtifactSource source = () -> new ByteArrayInputStream(artifact);
        Path runtime = this.temp.resolve("runtime");

        SpamserviceManager.ExtractedArtifact first = SpamserviceManager.extractArtifact(source, runtime);
        SpamserviceManager.ExtractedArtifact second = SpamserviceManager.extractArtifact(source, runtime);

        assertThat(first).isEqualTo(second);
        assertThat(first.path().getFileName().toString())
            .isEqualTo("spamservice-server-" + first.sha256() + ".jar");
        assertThat(Files.readAllBytes(first.path())).isEqualTo(artifact);
        assertThat(Files.getPosixFilePermissions(first.path())).noneMatch(permission ->
            permission == java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                || permission == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
                || permission == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
        try (var files = Files.list(runtime)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1);
        }
    }

    @Test
    void occupiedLoopbackPortFailsPreflight() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                SpamserviceManager.ensurePortAvailable(occupied.getLocalPort()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already in use");
        }
    }

    @Test
    void readinessRequiresTheCurrentLaunchNonce() throws Exception {
        ProcessBuilder builder = SystemUsers.executionBuilder(null,
            SystemUsers.safeEnvironment(this.temp.toString()), List.of("/bin/sleep", "60"), true);
        ManagedServiceProcess process = ManagedServiceProcess.start(builder, null, value -> value);
        try {
            String base = "http://127.0.0.1:" + this.port;
            assertThat(SpamserviceManager.probeReadiness(base, process, "expected", 300, () -> false))
                .isFalse();
            this.readinessNonce = "expected";
            assertThat(SpamserviceManager.probeReadiness(base, process, "expected", 1_000, () -> false))
                .isTrue();
        } finally {
            process.stop(100);
        }
    }

    @Test
    void runtimeLayoutRejectsSymlinksAndNeverHandsOwnershipTheExecutableDirectory() throws Exception {
        Path root = this.temp.resolve("layout");
        Path outside = this.temp.resolve("outside");
        Files.createDirectories(outside);
        Files.createDirectories(root.resolve("managed-services"));
        Files.createSymbolicLink(root.resolve("managed-services/spamservice"), outside);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            SpamserviceManager.prepareRuntimePaths(root,
                new SystemUsers.RunAsUser("spamservice", 4242, 4242, this.temp.toString()),
                (path, runAs) -> {}))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("symlink");

        Files.delete(root.resolve("managed-services/spamservice"));
        List<Path> ownershipTargets = new ArrayList<>();
        SpamserviceManager.RuntimePaths paths = SpamserviceManager.prepareRuntimePaths(root,
            new SystemUsers.RunAsUser("spamservice", 4242, 4242, this.temp.toString()),
            (path, runAs) -> ownershipTargets.add(path));
        assertThat(ownershipTargets).containsExactly(paths.dataDirectory(), paths.settingsDirectory(),
            paths.tempDirectory(), paths.instanceDirectory());
        assertThat(ownershipTargets).doesNotContain(paths.managedRoot(), paths.executableDirectory());
        assertThat(Files.getPosixFilePermissions(paths.instanceDirectory())).contains(
            PosixFilePermission.OTHERS_EXECUTE).doesNotContain(PosixFilePermission.OTHERS_READ);
    }

    @Test
    void ownershipTransferUsesTheSudoBrokerWithoutShellParsing() {
        assertThat(SpamserviceManager.ownershipCommand(Path.of("/tmp/runtime with spaces"),
            new SystemUsers.RunAsUser("spamservice", 4242, 4343, "/tmp")))
            .containsExactly("/usr/bin/sudo", "-n", "--", "/usr/bin/chown", "--no-dereference",
                "4242:4343", "--", "/tmp/runtime with spaces");
    }

    @Test
    void migrationsPrecedeStartAndReadyPublishesDerivedIntegration() throws Exception {
        FakeStore store = new FakeStore(config(true, "controller-super-secret"));
        RecordingLauncher launcher = new RecordingLauncher(false);
        RecordingSink sink = new RecordingSink();
        AtomicInteger readinessCalls = new AtomicInteger();
        SpamserviceManager manager = manager(store, launcher,
            (base, process, nonce, timeout, cancelled) -> {
                readinessCalls.incrementAndGet();
                return true;
            }, sink);
        try {
            manager.start();
            await(() -> "ready".equals(manager.snapshot().state()), 5_000);

            assertThat(launcher.commands).hasSize(2);
            assertThat(launcher.commands.get(0)).contains("--run-migrations");
            assertThat(launcher.commands.get(1)).doesNotContain("--run-migrations");
            assertThat(launcher.commands).allSatisfy(command ->
                assertThat(command).noneMatch(argument -> argument.contains("controller-super-secret")));
            assertThat(launcher.environments).allSatisfy(environment -> assertThat(environment)
                .doesNotContainKey("SPAMSERVICE_CONTROLLER_KEY")
                .containsEntry("PORT", String.valueOf(this.port))
                .containsEntry("ZENIT__NETWORK__PORT", String.valueOf(this.port))
                .containsEntry("ZENIT__NETWORK__BIND_ADDRESS", "127.0.0.1"));
            assertThat(launcher.environments.get(0)).doesNotContainKey("SPAMSERVICE_CONTROLLER_STDIN");
            assertThat(launcher.environments.get(1)).containsEntry("SPAMSERVICE_CONTROLLER_STDIN", "1");
            assertThat(launcher.stdinLines).containsExactly(null, "controller-super-secret");
            assertThat(readinessCalls).hasValue(1);
            assertThat(manager.snapshot().state()).isEqualTo("ready");
            assertThat(manager.baseUrl()).isEqualTo("http://127.0.0.1:" + this.port);
            assertThat(manager.client()).isNotNull();

            String expectedReportingKey = SpamserviceManager.installationReportingKey(
                "controller-super-secret");
            assertThat(sink.url).isEqualTo("http://127.0.0.1:" + this.port + "/v1/events");
            assertThat(sink.token).isEqualTo(expectedReportingKey);
            assertThat(this.ensureBodies).singleElement().satisfies(body -> assertThat(body)
                .contains("\"external_id\":\"hohenheim:installation\"")
                .contains("\"trusted\":true")
                .contains("\"key\":\"" + expectedReportingKey + "\""));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void configuredReportingUrlIsAvailableBeforeRuntimeReadiness() {
        SpamserviceManager manager = manager(new FakeStore(config(true, "controller-key")),
            new RecordingLauncher(false), (base, process, nonce, timeout, cancelled) -> false,
            new RecordingSink());
        try {
            assertThat(manager.baseUrl()).isNull();
            assertThat(manager.reportingBaseUrl()).isEqualTo("http://127.0.0.1:" + this.port);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingControllerKeyIsGeneratedPersistedAndNeverPutInArgv() throws Exception {
        FakeStore store = new FakeStore(config(true, null));
        RecordingLauncher launcher = new RecordingLauncher(false);
        SpamserviceManager manager = manager(store, launcher,
            (base, process, nonce, timeout, cancelled) -> true,
            new RecordingSink());
        try {
            manager.start();
            await(() -> "ready".equals(manager.snapshot().state()), 5_000);
            assertThat(store.persistedKey).startsWith("spam_");
            assertThat(launcher.environments).allSatisfy(environment ->
                assertThat(environment).doesNotContainKey("SPAMSERVICE_CONTROLLER_KEY"));
            assertThat(launcher.stdinLines.get(1)).isEqualTo(store.persistedKey);
            assertThat(launcher.commands).allSatisfy(command ->
                assertThat(command).noneMatch(argument -> argument.contains(store.persistedKey)));
            assertThat(manager.snapshot().lastError()).isNull();
            assertThat(launcher.processes).allSatisfy(process ->
                assertThat(process.output()).doesNotContain(store.persistedKey));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void readinessFailureStaysFailOpenAndCleansUpTheProcess() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(false);
        RecordingSink sink = new RecordingSink();
        SpamserviceManager manager = manager(new FakeStore(config(true, "key")), launcher,
            (base, process, nonce, timeout, cancelled) -> false, sink);
        try {
            manager.start();
            await(() -> "backoff".equals(manager.snapshot().state()), 5_000);
            assertThat(manager.client()).isNull();
            assertThat(manager.baseUrl()).isNull();
            assertThat(manager.snapshot().state()).isEqualTo("backoff");
            assertThat(launcher.processes.get(1).isAlive()).isFalse();
            assertThat(sink.url).isNull();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedProcessGroupCleanupBlocksEveryRestart() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        ProcessGroupSupport.Operator failedCleanup = new ProcessGroupSupport.Operator() {
            @Override public ProcessGroupSupport.SignalResult signal(SystemUsers.RunAsUser runAs,
                                                                      long processGroupId,
                                                                      ProcessGroupSupport.Signal signal) {
                return ProcessGroupSupport.SignalResult.FAILED;
            }

            @Override public ProcessGroupSupport.GroupState probe(SystemUsers.RunAsUser runAs,
                                                                   long processGroupId) {
                return ProcessGroupSupport.GroupState.UNKNOWN;
            }
        };
        SpamserviceManager.ProcessLauncher launcher = (requested, runAs, redactor, stdinLine) -> {
            List<String> command = List.copyOf(requested.command());
            commands.add(command);
            boolean migration = command.contains("--run-migrations");
            ProcessBuilder actual = SystemUsers.executionBuilder(null, requested.environment(),
                List.of("/bin/sh", "-c", migration ? "exit 0" : "sleep 60"), true);
            return migration
                ? ManagedServiceProcess.start(actual, null, redactor, stdinLine)
                : ManagedServiceProcess.start(actual, null, redactor, failedCleanup, stdinLine);
        };
        SpamserviceManager manager = new SpamserviceManager(new FakeStore(config(true, "key")),
            () -> new ByteArrayInputStream("artifact".getBytes(StandardCharsets.UTF_8)), launcher,
            (base, process, nonce, timeout, cancelled) -> false, (path, runAs) -> {},
            new RecordingSink(), () -> this.temp.resolve("blocked-cleanup"), () -> {},
            port -> {}, scheduler());
        try {
            manager.start();
            await(() -> "failed".equals(manager.snapshot().state()), 5_000);
            assertThat(manager.snapshot().pid()).isNotNull();
            Thread.sleep(1_200);
            assertThat(commands).hasSize(2);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void disabledStateStartsNothingAndLeavesManagedSinkLifecycleForShutdown() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(false);
        RecordingSink sink = new RecordingSink();
        SpamserviceManager manager = manager(new FakeStore(config(false, "key")), launcher,
            (base, process, nonce, timeout, cancelled) -> true, sink);
        try {
            manager.reconcile();
            await(() -> "disabled".equals(manager.snapshot().state()), 2_000);
            assertThat(launcher.commands).isEmpty();
            assertThat(manager.snapshot().state()).isEqualTo("disabled");
            assertThat(manager.client()).isNull();
            assertThat(sink.uninstalls).isZero();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void sharedNonRootSystemUserIsRejectedBeforeAnyProcessLaunch() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(false);
        SpamserviceManager.Config shared = new SpamserviceManager.Config(1, true, this.port,
            new SystemUsers.RunAsUser("site-owner", 4242, 4242, this.temp.toString()), 256, "key");
        SpamserviceManager manager = manager(new FakeStore(shared), launcher,
            (base, process, nonce, timeout, cancelled) -> true, new RecordingSink());
        try {
            manager.start();
            await(() -> "backoff".equals(manager.snapshot().state()), 2_000);
            assertThat(launcher.commands).isEmpty();
            assertThat(manager.snapshot().lastError()).contains("dedicated non-root 'spamservice'");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void unexpectedExitRecoversThroughBoundedBackoff() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(false);
        RecordingSink sink = new RecordingSink();
        SpamserviceManager manager = manager(new FakeStore(config(true, "key")), launcher,
            (base, process, nonce, timeout, cancelled) -> true, sink);
        try {
            manager.start();
            await(() -> "ready".equals(manager.snapshot().state()), 5_000);
            assertThat(manager.snapshot().state()).isEqualTo("ready");
            launcher.processes.get(1).stop(100);

            await(() -> launcher.commands.size() >= 4, 5_000);
            await(() -> "ready".equals(manager.snapshot().state()), 5_000);
            assertThat(manager.snapshot().consecutiveCrashes()).isEqualTo(1);
            assertThat(launcher.commands.get(2)).contains("--run-migrations");
            assertThat(launcher.commands.get(3)).doesNotContain("--run-migrations");
            assertThat(sink.installs).isEqualTo(2);
            assertThat(sink.uninstalls).isZero();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void bootNeverWaitsForInstallationLoading() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SpamserviceManager.InstallationStore blockingStore = new SpamserviceManager.InstallationStore() {
            @Override public SpamserviceManager.Config load() {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return config(false, "key");
            }
            @Override public String persistControllerKey(int id, String key) { return key; }
        };
        SpamserviceManager manager = new SpamserviceManager(blockingStore,
            () -> new ByteArrayInputStream(new byte[] {1}), new RecordingLauncher(false),
            (base, process, nonce, timeout, cancelled) -> true, (path, runAs) -> {},
            new RecordingSink(), () -> this.temp.resolve("runtime-root"), () -> {}, port -> {}, scheduler());
        try {
            long started = System.nanoTime();
            manager.boot();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).isLessThan(100);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long stopping = System.nanoTime();
            manager.stop();
            long stopElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopping);
            assertThat(stopElapsedMs).isLessThan(100);
            release.countDown();
            await(() -> "stopped".equals(manager.snapshot().state()), 2_000);
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    private SpamserviceManager manager(SpamserviceManager.InstallationStore store,
                                       RecordingLauncher launcher,
                                       SpamserviceManager.ReadinessProbe readiness,
                                       RecordingSink sink) {
        return new SpamserviceManager(store,
            () -> new ByteArrayInputStream("artifact".getBytes(StandardCharsets.UTF_8)),
            launcher, readiness, (path, runAs) -> {}, sink,
            () -> this.temp.resolve("runtime-root"), () -> {}, port -> {}, scheduler());
    }

    private SpamserviceManager.Config config(boolean enabled, String key) {
        return new SpamserviceManager.Config(1, enabled, this.port,
            new SystemUsers.RunAsUser("spamservice", 4242, 4242, this.temp.toString()), 256, key);
    }

    private ScheduledExecutorService scheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon().name("spamservice-manager-test").unstarted(runnable));
    }

    private void ensureClient(HttpExchange exchange) throws IOException {
        synchronized (this.ensureBodies) {
            this.ensureBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] response = ("{\"client_id\":\"client-id\","
            + "\"external_id\":\"hohenheim:installation\",\"created\":true,\"key_added\":true}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void await(Check condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.ok() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.ok()).isTrue();
    }

    private final class RecordingLauncher implements SpamserviceManager.ProcessLauncher {
        private final boolean serverExits;
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> environments = new ArrayList<>();
        final List<String> stdinLines = new ArrayList<>();
        final List<ManagedServiceProcess> processes = new ArrayList<>();

        RecordingLauncher(boolean serverExits) {
            this.serverExits = serverExits;
        }

        @Override
        public synchronized ManagedServiceProcess launch(ProcessBuilder requested,
                                                          SystemUsers.RunAsUser runAs,
                                                          UnaryOperator<String> redactor,
                                                          String stdinLine)
                throws IOException {
            List<String> command = List.copyOf(requested.command());
            this.commands.add(command);
            this.environments.add(Map.copyOf(requested.environment()));
            this.stdinLines.add(stdinLine);
            boolean migration = command.contains("--run-migrations");
            ProcessBuilder actual = SystemUsers.executionBuilder(null, requested.environment(),
                List.of("/bin/sh", "-c", migration || this.serverExits ? "exit 0" : "sleep 60"),
                true);
            ManagedServiceProcess process = ManagedServiceProcess.start(actual, null, redactor, stdinLine);
            this.processes.add(process);
            return process;
        }
    }

    private static final class FakeStore implements SpamserviceManager.InstallationStore {
        private SpamserviceManager.Config config;
        private String persistedKey;

        FakeStore(SpamserviceManager.Config config) {
            this.config = config;
        }

        @Override public SpamserviceManager.Config load() { return this.config; }
        @Override public String persistControllerKey(int id, String key) {
            this.persistedKey = key;
            this.config = this.config.withControllerKey(key);
            return key;
        }
    }

    private static final class RecordingSink implements SpamserviceManager.SinkInstaller {
        private String url;
        private String token;
        private int installs;
        private int uninstalls;

        @Override public void install(String url, String token) {
            this.url = url;
            this.token = token;
            this.installs++;
        }
        @Override public void uninstall() {
            this.url = null;
            this.token = null;
            this.uninstalls++;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
