package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.security.SecurityReportEnv;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.server.security.SecurityEventSinks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/** Supervises Hohenheim's one nested local Spamservice distribution. */
public final class SpamserviceManager {

    public static final String ARTIFACT_RESOURCE = "managed-services/spamservice-server.jar";
    public static final String INSTALLATION_EXTERNAL_ID = "hohenheim:installation";
    public static final String SITE_EXTERNAL_ID_PREFIX = "hohenheim:site:";
    public static final String EVENTS_PATH = "/v1/events";
    public static final String DEDICATED_SYSTEM_USER = "spamservice";

    static final long MIGRATION_TIMEOUT_MS = 120_000;
    static final long READINESS_TIMEOUT_MS = 30_000;
    static final long STOP_TIMEOUT_MS = 5_000;
    static final long INITIAL_BACKOFF_MS = 1_000;
    static final long MAX_BACKOFF_MS = 60_000;
    static final long STABLE_RUNTIME_MS = 60_000;

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_WITH_TRAVERSE = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);
    private static final Set<PosixFilePermission> TRAVERSABLE = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE);

    private static final SpamserviceManager INSTANCE = new SpamserviceManager();

    private final Object lock = new Object();
    private final InstallationStore store;
    private final ArtifactSource artifactSource;
    private final ProcessLauncher processLauncher;
    private final ReadinessProbe readinessProbe;
    private final Ownership ownership;
    private final SinkInstaller sinkInstaller;
    private final RuntimeRoot runtimeRoot;
    private final ReporterReconciler reporterReconciler;
    private final PortPreflight portPreflight;
    private final ScheduledExecutorService lifecycle;

    private volatile boolean booted;
    private volatile boolean shuttingDown;
    private volatile boolean desiredRunning;
    private volatile @Nullable ManagedServiceProcess process;
    private volatile @Nullable Config activeConfig;
    private volatile @Nullable SpamserviceClient client;
    private volatile @Nullable String baseUrl;
    private volatile @Nullable String artifactHash;
    private volatile @Nullable String lastError;
    private volatile String state = "stopped";
    private volatile boolean configurationPresent;
    private volatile boolean configurationEnabled;
    private volatile int consecutiveCrashes;
    private volatile long readyAtMs;
    private long generation;
    private boolean cleanupBlocked;
    private @Nullable ScheduledFuture<?> retry;

    private SpamserviceManager() {
        this(new OrmInstallationStore(), SpamserviceManager::openNestedArtifact,
            (builder, runAs, redactor, stdinLine) ->
                ManagedServiceProcess.start(builder, runAs, redactor, stdinLine),
            SpamserviceManager::probeReadiness, SpamserviceManager::ensureOwned,
            new FrameworkSinkInstaller(), SpamserviceManager::configuredRuntimeRoot,
            SecurityReportEnv::reconcilePersistedReporters, SpamserviceManager::ensurePortAvailable,
            newLifecycleExecutor());
    }

    SpamserviceManager(@NonNull InstallationStore store,
                       @NonNull ArtifactSource artifactSource,
                       @NonNull ProcessLauncher processLauncher,
                       @NonNull ReadinessProbe readinessProbe,
                       @NonNull Ownership ownership,
                       @NonNull SinkInstaller sinkInstaller,
                       @NonNull RuntimeRoot runtimeRoot,
                       @NonNull ReporterReconciler reporterReconciler,
                       @NonNull PortPreflight portPreflight,
                       @NonNull ScheduledExecutorService lifecycle) {
        this.store = store;
        this.artifactSource = artifactSource;
        this.processLauncher = processLauncher;
        this.readinessProbe = readinessProbe;
        this.ownership = ownership;
        this.sinkInstaller = sinkInstaller;
        this.runtimeRoot = runtimeRoot;
        this.reporterReconciler = reporterReconciler;
        this.portPreflight = portPreflight;
        this.lifecycle = lifecycle;
    }

    public static @NonNull SpamserviceManager get() {
        return INSTANCE;
    }

    /** Queues initial reconciliation and returns immediately. */
    public void boot() {
        long requested;
        synchronized (this.lock) {
            if (this.booted || this.shuttingDown) {
                return;
            }
            this.booted = true;
            this.desiredRunning = true;
            requested = ++this.generation;
        }
        submit(() -> reconcileGeneration(requested, false));
    }

    /** Cancels startup, performs bounded process-group cleanup, and releases the managed sink. */
    public void shutdown() {
        ManagedServiceProcess current;
        synchronized (this.lock) {
            if (this.shuttingDown) {
                return;
            }
            this.shuttingDown = true;
            this.desiredRunning = false;
            ++this.generation;
            cancelRetryLocked();
            current = this.process;
        }
        if (current != null) {
            current.process().destroy();
        }
        this.lifecycle.shutdownNow();
        if (current != null && !current.stop(STOP_TIMEOUT_MS)) {
            synchronized (this.lock) {
                this.cleanupBlocked = true;
                this.lastError = "Spamservice process-group cleanup was incomplete during shutdown";
                this.state = "failed";
            }
        } else {
            synchronized (this.lock) {
                if (this.process == current) {
                    this.process = null;
                }
                this.state = "stopped";
            }
        }
        this.client = null;
        this.baseUrl = null;
        this.sinkInstaller.uninstall();
    }

    /** Queues reconciliation with the current singleton row and returns immediately. */
    public void reconcile() {
        queueDesiredReconcile(false);
    }

    public void start() {
        queueDesiredReconcile(false);
    }

    public void stop() {
        long requested;
        ManagedServiceProcess current;
        synchronized (this.lock) {
            if (this.shuttingDown) {
                return;
            }
            this.desiredRunning = false;
            requested = ++this.generation;
            cancelRetryLocked();
            this.state = "stopping";
            current = this.process;
        }
        if (current != null) {
            current.process().destroy();
        }
        submit(() -> stopGeneration(requested, "stopped"));
    }

    public void restart() {
        queueDesiredReconcile(true);
    }

    private void queueDesiredReconcile(boolean forceRestart) {
        long requested;
        ManagedServiceProcess current = null;
        synchronized (this.lock) {
            if (this.shuttingDown) {
                throw new IllegalStateException("Spamservice manager is shut down");
            }
            this.desiredRunning = true;
            requested = ++this.generation;
            cancelRetryLocked();
            if (forceRestart) {
                this.state = "restarting";
                current = this.process;
            }
        }
        if (current != null) {
            current.process().destroy();
        }
        submit(() -> reconcileGeneration(requested, forceRestart));
    }

    public @Nullable SpamserviceClient client() {
        return this.client;
    }

    public @NonNull SpamserviceClient requireClient() {
        SpamserviceClient available = client();
        if (available == null) {
            throw new IllegalStateException("The local Spamservice runtime is not ready");
        }
        return available;
    }

    public @Nullable String baseUrl() {
        return this.baseUrl;
    }

    /** Returns the configured reporting endpoint even while the managed runtime is starting. */
    public @Nullable String reportingBaseUrl() {
        String ready = this.baseUrl;
        if (ready != null) {
            return ready;
        }
        try {
            Config configured = this.store.load();
            return configured != null && configured.enabled() ? baseUrl(configured.port()) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public @NonNull Snapshot snapshot() {
        ManagedServiceProcess current = this.process;
        return new Snapshot(this.configurationPresent, this.configurationEnabled, this.state,
            current != null ? current.pid() : null, this.baseUrl, this.artifactHash,
            this.consecutiveCrashes, this.lastError);
    }

    private void reconcileGeneration(long requested, boolean forceRestart) {
        try {
            ensureCurrent(requested);
            Config loaded = this.store.load();
            synchronized (this.lock) {
                ensureCurrentLocked(requested);
                this.configurationPresent = loaded != null;
                this.configurationEnabled = loaded != null && loaded.enabled();
            }
            if (loaded == null || !loaded.enabled()) {
                synchronized (this.lock) {
                    if (this.generation == requested) {
                        this.desiredRunning = false;
                    }
                }
                stopGeneration(requested, "disabled");
                this.lastError = null;
                return;
            }

            Config config = validate(loaded);
            ManagedServiceProcess current = this.process;
            if (!forceRestart && current != null && current.isAlive()
                    && config.equals(this.activeConfig)) {
                if (this.client == null || !"ready".equals(this.state)) {
                    installIntegration(requested, config, baseUrl(config.port()));
                }
                return;
            }
            if (!stopOwnedProcess(requested, "stopped")) {
                return;
            }
            startGeneration(requested, config);
        } catch (Cancelled ignored) {
            stopOwnedProcess(requested, "stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failStart(requested, "Spamservice startup interrupted");
        } catch (Exception e) {
            failStart(requested, messageOf(e));
        }
    }

    private void startGeneration(long requested, Config original) throws Exception {
        Config config = original;
        setState(requested, "preparing");
        RuntimePaths paths = prepareRuntimePaths(this.runtimeRoot.resolve(), config.runAs(), this.ownership);

        if (config.controllerKey() == null || config.controllerKey().isBlank()) {
            String generated = "spam_" + SecureTokens.randomToken(32);
            config = config.withControllerKey(this.store.persistControllerKey(config.id(), generated));
        }

        ExtractedArtifact artifact = extractArtifact(this.artifactSource, paths.executableDirectory());
        this.artifactHash = artifact.sha256();
        verifyArtifact(artifact);
        synchronized (this.lock) {
            ensureCurrentLocked(requested);
            this.activeConfig = config;
        }

        UnaryOperator<String> redactor = redactor(config.controllerKey());
        Map<String, String> migrationEnvironment = environment(config, paths, null, false);
        setState(requested, "migrating");
        ManagedServiceProcess migration = launch(config, paths.instanceDirectory(), migrationEnvironment,
            command(config.maxHeapMb(), artifact.path(), true), redactor, null);
        adoptProcess(requested, migration);
        boolean migrated = migration.waitFor(MIGRATION_TIMEOUT_MS);
        boolean migrationClean = migration.stop(STOP_TIMEOUT_MS);
        if (!migrationClean) {
            blockCleanup(migration, "Spamservice migration process-group cleanup was incomplete");
            return;
        }
        clearProcess(migration);
        ensureCurrent(requested);
        if (!migrated) {
            throw new IllegalStateException("Spamservice migrations timed out");
        }
        if (migration.exitValue() != 0) {
            throw new IllegalStateException("Spamservice migrations failed with exit "
                + migration.exitValue() + outputSuffix(migration.output()));
        }

        verifyArtifact(artifact);
        this.portPreflight.check(config.port());
        String nonce = SecureTokens.randomToken(24);
        Map<String, String> runtimeEnvironment = environment(config, paths, nonce, true);
        String runtimeBaseUrl = baseUrl(config.port());
        setState(requested, "starting");
        ManagedServiceProcess started = launch(config, paths.instanceDirectory(), runtimeEnvironment,
            command(config.maxHeapMb(), artifact.path(), false), redactor,
            Objects.requireNonNull(config.controllerKey()));
        adoptProcess(requested, started);
        Config startedConfig = config;
        started.process().onExit().thenRun(() -> submit(() -> processExited(started, startedConfig, requested)));

        boolean ready = this.readinessProbe.await(runtimeBaseUrl, started, nonce,
            READINESS_TIMEOUT_MS, () -> !isCurrent(requested));
        ensureCurrent(requested);
        if (!ready) {
            if (!started.stop(STOP_TIMEOUT_MS)) {
                blockCleanup(started, "Spamservice readiness cleanup was incomplete");
                return;
            }
            clearProcess(started);
            throw new IllegalStateException("Spamservice readiness timed out" + outputSuffix(started.output()));
        }

        this.readyAtMs = System.currentTimeMillis();
        installIntegration(requested, config, runtimeBaseUrl);
    }

    private ManagedServiceProcess launch(Config config, Path workingDirectory,
                                          Map<String, String> environment, List<String> command,
                                          UnaryOperator<String> redactor, @Nullable String stdinLine)
            throws IOException {
        ProcessBuilder builder = SystemUsers.executionBuilder(config.runAs(), environment, command, true);
        builder.directory(workingDirectory.toFile());
        return this.processLauncher.launch(builder, config.runAs(), redactor, stdinLine);
    }

    private void installIntegration(long requested, Config config, String runtimeBaseUrl) {
        try {
            ensureCurrent(requested);
            SpamserviceClient controller = SpamserviceClient.builder(runtimeBaseUrl,
                    Objects.requireNonNull(config.controllerKey()))
                .reputationTtl(reputationTtlMs())
                .build();
            String reportingKey = installationReportingKey(config.controllerKey());
            controller.ensureClient(INSTALLATION_EXTERNAL_ID, "Hohenheim installation", true, reportingKey);
            synchronized (this.lock) {
                ensureCurrentLocked(requested);
                this.baseUrl = runtimeBaseUrl;
                this.client = controller;
                this.lastError = null;
            }
            this.sinkInstaller.install(runtimeBaseUrl + EVENTS_PATH, reportingKey);
            synchronized (this.lock) {
                ensureCurrentLocked(requested);
                this.state = "ready";
            }
            this.reporterReconciler.reconcile();
        } catch (Cancelled ignored) {
        } catch (RuntimeException e) {
            synchronized (this.lock) {
                if (this.generation != requested) {
                    return;
                }
                this.client = null;
                this.baseUrl = null;
                this.state = "degraded";
                this.lastError = "Spamservice integration setup failed: " + messageOf(e);
                scheduleRetryLocked(requested, false);
            }
        }
    }

    private void processExited(ManagedServiceProcess exited, Config config, long requested) {
        boolean cleaned = exited.stop(STOP_TIMEOUT_MS);
        synchronized (this.lock) {
            if (this.process != exited) {
                return;
            }
            if (!cleaned) {
                blockCleanupLocked(exited, "Spamservice exited but its process group remains");
                return;
            }
            this.process = null;
            this.client = null;
            this.baseUrl = null;
            long uptime = this.readyAtMs == 0 ? 0 : System.currentTimeMillis() - this.readyAtMs;
            this.readyAtMs = 0;
            if (uptime >= STABLE_RUNTIME_MS) {
                this.consecutiveCrashes = 0;
            }
            if (this.generation != requested || this.shuttingDown || !this.desiredRunning
                    || !config.equals(this.activeConfig)) {
                return;
            }
            this.lastError = "Spamservice exited with code " + exited.exitValue()
                + outputSuffix(exited.output());
            this.state = "backoff";
            scheduleRetryLocked(requested, true);
        }
    }

    private void failStart(long requested, String error) {
        if (!isCurrent(requested)) {
            return;
        }
        if (!stopOwnedProcess(requested, "backoff")) {
            return;
        }
        synchronized (this.lock) {
            if (this.generation != requested) {
                return;
            }
            this.client = null;
            this.baseUrl = null;
            this.lastError = error;
            this.state = "backoff";
            if (this.desiredRunning && !this.shuttingDown) {
                scheduleRetryLocked(requested, true);
            }
        }
    }

    private void scheduleRetryLocked(long requested, boolean crash) {
        if (this.retry != null && !this.retry.isDone()) {
            return;
        }
        if (crash) {
            this.consecutiveCrashes++;
        }
        int shift = Math.min(Math.max(0, this.consecutiveCrashes - 1), 6);
        long delay = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << shift);
        this.retry = this.lifecycle.schedule(() -> {
            synchronized (this.lock) {
                if (this.generation != requested || !this.desiredRunning || this.shuttingDown) {
                    return;
                }
                this.retry = null;
            }
            reconcileGeneration(requested, false);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void stopGeneration(long requested, String nextState) {
        synchronized (this.lock) {
            if (this.generation != requested || this.shuttingDown) {
                return;
            }
        }
        stopOwnedProcess(requested, nextState);
    }

    private boolean stopOwnedProcess(long requested, String nextState) {
        ManagedServiceProcess current;
        synchronized (this.lock) {
            if (this.generation != requested) {
                return false;
            }
            cancelRetryLocked();
            current = this.process;
            this.client = null;
            this.baseUrl = null;
        }
        if (current != null && !current.stop(STOP_TIMEOUT_MS)) {
            blockCleanup(current, "Spamservice process-group cleanup was incomplete");
            return false;
        }
        synchronized (this.lock) {
            if (this.process == current) {
                this.process = null;
            }
            this.activeConfig = null;
            this.readyAtMs = 0;
            if (this.generation == requested) {
                this.state = nextState;
            }
        }
        return true;
    }

    private void blockCleanup(ManagedServiceProcess current, String error) {
        synchronized (this.lock) {
            blockCleanupLocked(current, error);
        }
    }

    private void blockCleanupLocked(ManagedServiceProcess current, String error) {
        this.process = current;
        this.cleanupBlocked = true;
        this.desiredRunning = false;
        this.client = null;
        this.baseUrl = null;
        this.lastError = error;
        this.state = "failed";
        cancelRetryLocked();
    }

    private void adoptProcess(long requested, ManagedServiceProcess owned) {
        synchronized (this.lock) {
            ensureCurrentLocked(requested);
            if (this.cleanupBlocked) {
                throw new IllegalStateException("Spamservice restart is blocked by incomplete process cleanup");
            }
            this.process = owned;
        }
    }

    private void clearProcess(ManagedServiceProcess owned) {
        synchronized (this.lock) {
            if (this.process == owned) {
                this.process = null;
            }
        }
    }

    private void setState(long requested, String value) {
        synchronized (this.lock) {
            ensureCurrentLocked(requested);
            this.state = value;
        }
    }

    private void cancelRetryLocked() {
        if (this.retry != null) {
            this.retry.cancel(false);
            this.retry = null;
        }
    }

    private boolean isCurrent(long requested) {
        synchronized (this.lock) {
            return this.generation == requested && this.desiredRunning && !this.shuttingDown;
        }
    }

    private void ensureCurrent(long requested) {
        synchronized (this.lock) {
            ensureCurrentLocked(requested);
        }
    }

    private void ensureCurrentLocked(long requested) {
        if (this.generation != requested || !this.desiredRunning || this.shuttingDown) {
            throw new Cancelled();
        }
    }

    private void submit(Runnable task) {
        try {
            this.lifecycle.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shutdown won the race with an on-exit callback or queued request.
        }
    }

    static @NonNull RuntimePaths prepareRuntimePaths(@NonNull Path configuredDataRoot,
                                                     SystemUsers.@NonNull RunAsUser runAs,
                                                     @NonNull Ownership ownership) throws IOException {
        Path dataRoot = configuredDataRoot.toAbsolutePath().normalize();
        createDirectoriesNoSymlinks(dataRoot);
        requireHohenheimControlled(dataRoot, runAs);
        Path managedRoot = dataRoot.resolve("managed-services").resolve("spamservice").normalize();
        if (!managedRoot.startsWith(dataRoot)) {
            throw new IOException("Spamservice managed root escapes the Hohenheim data root");
        }
        createDirectoriesNoSymlinks(managedRoot);
        Files.setPosixFilePermissions(managedRoot, TRAVERSABLE);
        requireHohenheimControlled(managedRoot, runAs);
        Path realDataRoot = dataRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realManagedRoot = managedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realManagedRoot.startsWith(realDataRoot)) {
            throw new IOException("Spamservice managed root resolves outside the Hohenheim data root");
        }

        Path executable = managedRoot.resolve("runtime");
        Path instance = managedRoot.resolve("instance");
        Path data = instance.resolve("data");
        Path settings = instance.resolve("settings");
        Path temp = instance.resolve("tmp");
        createDirectoriesNoSymlinks(executable);
        Files.setPosixFilePermissions(executable, TRAVERSABLE);
        requireHohenheimControlled(executable, runAs);
        List<Path> childDirectories = List.of(instance, data, settings, temp);
        for (Path directory : childDirectories) {
            createDirectoriesNoSymlinks(directory);
        }
        for (Path directory : List.of(data, settings, temp, instance)) {
            Set<PosixFilePermission> permissions = directory.equals(instance)
                ? OWNER_WITH_TRAVERSE : OWNER_ONLY;
            Map<String, Object> attributes = Files.readAttributes(
                directory, "unix:uid,gid", LinkOption.NOFOLLOW_LINKS);
            boolean owned = Integer.valueOf(runAs.uid()).equals(attributes.get("uid"))
                && (runAs.gid() == null || runAs.gid().equals(attributes.get("gid")));
            if (owned) {
                if (!permissions.equals(Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException("Managed Spamservice path has unexpected permissions: " + directory);
                }
                continue;
            }
            requireHohenheimControlled(directory, runAs);
            Files.setPosixFilePermissions(directory, permissions);
            ownership.ensure(directory, runAs);
        }
        return new RuntimePaths(managedRoot, executable, instance, data, settings, temp);
    }

    static @NonNull ExtractedArtifact extractArtifact(@NonNull ArtifactSource source,
                                                       @NonNull Path executableDirectory) throws Exception {
        createDirectoriesNoSymlinks(executableDirectory);
        Path temporary = Files.createTempFile(executableDirectory, ".spamservice-server-", ".jar.part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (InputStream input = source.open();
                 FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Channels.newOutputStream(channel)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                output.flush();
                channel.force(true);
            }
            String hash = hex(digest.digest());
            Path target = executableDirectory.resolve("spamservice-server-" + hash + ".jar");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !hash.equals(sha256(target))) {
                    throw new IOException("Existing Spamservice artifact is not the expected regular file: " + target);
                }
                Files.deleteIfExists(temporary);
            } else {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("Atomic Spamservice artifact installation is unsupported for "
                        + executableDirectory, e);
                }
            }
            Files.setPosixFilePermissions(target, READ_ONLY);
            cleanupOldArtifacts(executableDirectory, target);
            return new ExtractedArtifact(target, hash);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void verifyArtifact(@NonNull ExtractedArtifact artifact) throws Exception {
        if (Files.isSymbolicLink(artifact.path())
                || !Files.isRegularFile(artifact.path(), LinkOption.NOFOLLOW_LINKS)
                || !artifact.sha256().equals(sha256(artifact.path()))) {
            throw new IOException("Spamservice artifact changed after extraction: " + artifact.path());
        }
    }

    static void ensurePortAvailable(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new IOException("Spamservice port " + port + " is already in use on 127.0.0.1", e);
        }
    }

    private static @NonNull Config validate(@NonNull Config config) {
        if (!DEDICATED_SYSTEM_USER.equals(config.runAs().name()) || config.runAs().uid() == 0) {
            throw new IllegalStateException("Spamservice requires the dedicated non-root 'spamservice' system user");
        }
        if (config.port() < SpamserviceInstallationModel.MIN_PORT
                || config.port() > SpamserviceInstallationModel.MAX_PORT) {
            throw new IllegalStateException("Spamservice port is outside 1024..65535");
        }
        int heap = Math.max(SpamserviceInstallationModel.MIN_HEAP_SIZE_MB,
            Math.min(SpamserviceInstallationModel.MAX_HEAP_SIZE_MB, config.maxHeapMb()));
        return config.withMaxHeapMb(heap);
    }

    static @NonNull Map<String, String> environment(@NonNull Config config,
                                                     @NonNull RuntimePaths paths,
                                                     @Nullable String launchNonce,
                                                     boolean controllerStdin) {
        Map<String, String> environment = new LinkedHashMap<>(
            SystemUsers.safeEnvironment(config.runAs().home()));
        environment.put("PORT", String.valueOf(config.port()));
        environment.put("SPAMSERVICE_PORT", String.valueOf(config.port()));
        environment.put("ZENIT__NETWORK__PORT", String.valueOf(config.port()));
        environment.put("ZENIT__NETWORK__BIND_ADDRESS", "127.0.0.1");
        environment.put("ZENIT_DB_URL", "jdbc:sqlite:" + paths.dataDirectory().resolve("spamservice.db"));
        environment.put("JANEWAY_DISABLED", "1");
        environment.put("TMPDIR", paths.tempDirectory().toString());
        if (controllerStdin) {
            environment.put("SPAMSERVICE_CONTROLLER_STDIN", "1");
        }
        if (launchNonce != null) {
            environment.put("SPAMSERVICE_LAUNCH_NONCE", launchNonce);
        }
        return environment;
    }

    static @NonNull List<String> command(int heapMb, @NonNull Path jar, boolean migrations) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx" + heapMb + "m");
        command.add("-jar");
        command.add(jar.toString());
        if (migrations) {
            command.add("--run-migrations");
        }
        return List.copyOf(command);
    }

    public static @NonNull String siteExternalId(int siteId) {
        return SITE_EXTERNAL_ID_PREFIX + siteId;
    }

    static @NonNull String installationReportingKey(@NonNull String controllerKey) {
        return "spam_" + SecureTokens.hmacSha256Hex(controllerKey,
            "hohenheim-installation-security-reporter-v1");
    }

    private static UnaryOperator<String> redactor(String secret) {
        return text -> text == null ? "" : text.replace(secret, "[REDACTED]");
    }

    private static long reputationTtlMs() {
        Integer seconds = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.REPUTATION_TTL_SECONDS);
        return Math.max(1, seconds != null ? seconds : 300) * 1000L;
    }

    private static String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static Path configuredRuntimeRoot() {
        String configured = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        return Path.of(configured == null || configured.isBlank() ? "data" : configured);
    }

    private static InputStream openNestedArtifact() throws IOException {
        InputStream input = SpamserviceManager.class.getClassLoader().getResourceAsStream(ARTIFACT_RESOURCE);
        if (input == null) {
            throw new IOException("Nested Spamservice artifact is missing: " + ARTIFACT_RESOURCE);
        }
        return input;
    }

    static boolean probeReadiness(String baseUrl, ManagedServiceProcess process,
                                  String launchNonce, long timeoutMs,
                                  BooleanSupplier cancelled) throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/health/ready"))
            .timeout(Duration.ofSeconds(1)).GET().build();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!cancelled.getAsBoolean() && process.isAlive() && System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && response.headers().firstValue("X-Spamservice-Launch-Nonce")
                            .filter(launchNonce::equals).isPresent()) {
                    return true;
                }
            } catch (IOException ignored) {
                // Binding and bootstrapping are still in progress.
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static void ensureOwned(Path path, SystemUsers.RunAsUser runAs) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed Spamservice path is not a real directory: " + path);
        }
        Map<String, Object> attributes = Files.readAttributes(path, "unix:uid,gid", LinkOption.NOFOLLOW_LINKS);
        boolean owned = Integer.valueOf(runAs.uid()).equals(attributes.get("uid"))
            && (runAs.gid() == null || runAs.gid().equals(attributes.get("gid")));
        if (owned) {
            return;
        }

        ProcessBuilder builder = new ProcessBuilder(ownershipCommand(path, runAs));
        SystemUsers.setEnvironment(builder, SystemUsers.safeEnvironment(System.getProperty("user.home")));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream stdout = process.getInputStream()) {
            output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Could not assign Spamservice path ownership"
                    + (output.isEmpty() ? "" : ": " + output));
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while assigning Spamservice path ownership", exception);
        }

        attributes = Files.readAttributes(path, "unix:uid,gid", LinkOption.NOFOLLOW_LINKS);
        if (!Integer.valueOf(runAs.uid()).equals(attributes.get("uid"))
                || (runAs.gid() != null && !runAs.gid().equals(attributes.get("gid")))) {
            throw new IOException("Spamservice path ownership did not change as requested: " + path);
        }
    }

    static @NonNull List<String> ownershipCommand(@NonNull Path path,
                                                   SystemUsers.@NonNull RunAsUser runAs) {
        String owner = String.valueOf(runAs.uid());
        if (runAs.gid() != null) {
            owner += ":" + runAs.gid();
        }
        return List.of("/usr/bin/sudo", "-n", "--", "/usr/bin/chown", "--no-dereference",
            owner, "--", path.toAbsolutePath().normalize().toString());
    }

    private static void createDirectoriesNoSymlinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Managed Spamservice path contains a symlink or non-directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static void requireHohenheimControlled(Path path, SystemUsers.RunAsUser runAs) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (Integer.valueOf(runAs.uid()).equals(attributes.get("uid"))) {
            throw new IOException("Hohenheim-owned Spamservice path is owned by the child account: " + path);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("Hohenheim-owned Spamservice path is writable outside its owner: " + path);
        }
    }

    private static void cleanupOldArtifacts(Path directory, Path current) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!path.equals(current) && name.startsWith("spamservice-server-") && name.endsWith(".jar")
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static ScheduledExecutorService newLifecycleExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon().name("spamservice-manager").unstarted(runnable));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private static String outputSuffix(String output) {
        String trimmed = output == null ? "" : output.trim();
        if (trimmed.length() > 4_096) {
            trimmed = trimmed.substring(trimmed.length() - 4_096);
        }
        return trimmed.isEmpty() ? "" : ": " + trimmed;
    }

    public record Snapshot(boolean configured, boolean enabled, @NonNull String state,
                           @Nullable Long pid, @Nullable String baseUrl,
                           @Nullable String artifactHash, int consecutiveCrashes,
                           @Nullable String lastError) {}

    record Config(int id, boolean enabled, int port, SystemUsers.@NonNull RunAsUser runAs,
                  int maxHeapMb, @Nullable String controllerKey) {
        Config withControllerKey(String value) {
            return new Config(id, enabled, port, runAs, maxHeapMb, value);
        }

        Config withMaxHeapMb(int value) {
            return new Config(id, enabled, port, runAs, value, controllerKey);
        }
    }

    record RuntimePaths(@NonNull Path managedRoot, @NonNull Path executableDirectory,
                        @NonNull Path instanceDirectory, @NonNull Path dataDirectory,
                        @NonNull Path settingsDirectory, @NonNull Path tempDirectory) {}

    record ExtractedArtifact(@NonNull Path path, @NonNull String sha256) {}

    interface InstallationStore {
        @Nullable Config load();
        @NonNull String persistControllerKey(int id, @NonNull String key);
    }

    interface ArtifactSource {
        @NonNull InputStream open() throws IOException;
    }

    interface ProcessLauncher {
        @NonNull ManagedServiceProcess launch(@NonNull ProcessBuilder builder,
                                               SystemUsers.@NonNull RunAsUser runAs,
                                               @NonNull UnaryOperator<String> redactor,
                                               @Nullable String stdinLine) throws IOException;
    }

    interface ReadinessProbe {
        boolean await(@NonNull String baseUrl, @NonNull ManagedServiceProcess process,
                      @NonNull String launchNonce, long timeoutMs,
                      @NonNull BooleanSupplier cancelled) throws InterruptedException;
    }

    interface Ownership {
        void ensure(@NonNull Path path, SystemUsers.@NonNull RunAsUser runAs) throws IOException;
    }

    interface SinkInstaller {
        void install(@NonNull String url, @NonNull String token);
        void uninstall();
    }

    interface RuntimeRoot {
        @NonNull Path resolve();
    }

    interface ReporterReconciler {
        void reconcile();
    }

    interface PortPreflight {
        void check(int port) throws IOException;
    }

    private static final class OrmInstallationStore implements InstallationStore {
        @Override
        public @Nullable Config load() {
            Row row = Models.get(SpamserviceInstallationModel.class).installation();
            if (row == null) {
                return null;
            }
            Integer id = row.get(SpamserviceInstallationModel.ID);
            Integer port = row.get(SpamserviceInstallationModel.PORT);
            Integer userId = row.get(SpamserviceInstallationModel.SYSTEM_USER_ID);
            Integer heap = row.get(SpamserviceInstallationModel.MAX_HEAP_MB);
            if (id == null || port == null) {
                throw new IllegalStateException("Spamservice installation configuration is incomplete");
            }
            if (!Boolean.TRUE.equals(row.get(SpamserviceInstallationModel.ENABLED))) {
                return new Config(id, false, port,
                    new SystemUsers.RunAsUser(DEDICATED_SYSTEM_USER, -1, null, null),
                    heap != null ? heap : 512, row.get(SpamserviceInstallationModel.CONTROLLER_KEY));
            }
            if (userId == null) {
                throw new IllegalStateException("Spamservice requires the dedicated system user");
            }
            SystemUsers.RunAsUser runAs = SystemUsers.resolve(userId);
            if (runAs == null) {
                throw new IllegalStateException("Spamservice requires the dedicated system user");
            }
            return new Config(id, true, port, runAs, heap != null ? heap : 512,
                row.get(SpamserviceInstallationModel.CONTROLLER_KEY));
        }

        @Override
        public @NonNull String persistControllerKey(int id, @NonNull String key) {
            Models.get(SpamserviceInstallationModel.class).find()
                .where(SpamserviceInstallationModel.ID.eq(SpamserviceInstallationModel.SINGLETON_ID))
                .assignIfNull(SpamserviceInstallationModel.CONTROLLER_KEY, key)
                .updateAll();
            Row canonical = Models.get(SpamserviceInstallationModel.class).installation();
            if (canonical == null || canonical.get(SpamserviceInstallationModel.CONTROLLER_KEY) == null) {
                throw new IllegalStateException("Could not persist the Spamservice controller key");
            }
            return canonical.get(SpamserviceInstallationModel.CONTROLLER_KEY);
        }
    }

    private static final class FrameworkSinkInstaller implements SinkInstaller {
        private SecurityEventSinks.@Nullable RemoteOverride override;

        @Override
        public synchronized void install(String url, String token) {
            if (this.override == null) {
                this.override = SecurityEventSinks.installManagedRemote(url, token);
            } else {
                this.override.replace(url, token);
            }
        }

        @Override
        public synchronized void uninstall() {
            if (this.override != null) {
                this.override.close();
                this.override = null;
            }
            SecurityEventSinks.uninstallRemote();
        }
    }

    private static final class Cancelled extends RuntimeException {}
}
