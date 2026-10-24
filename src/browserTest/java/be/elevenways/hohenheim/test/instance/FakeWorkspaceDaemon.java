package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hermetic WORKSPACE: the real {@link WorkspaceKind} with two things swapped out -- the
 * runtime it deploys through, and the host preparation that would want a real filesystem.
 *
 * AIDEV-NOTE: the {@code FakeDockerDaemon} doctrine rather than the {@code
 * FakeNativeDaemons} one. A fake KIND of its own proves nothing about the workspace lane,
 * because {@code WorkspaceBuilds} refuses any record whose handler is not
 * {@code WorkspaceKind.ID} -- so the fake has to BE that kind, differing only in what it
 * talks to. Everything the tests are about (the deploy fold, the trigger policy, the
 * checkout, the build operation record) is the production code path.
 *
 * EVERY FAKE MUST BE ABLE TO SAY NO: the runtime here can be down ({@link #stop}), gone
 * ({@link #remove}) or unanswerable ({@link #setUnreachable}) -- the last one being the
 * distinction the trigger policy turns on.
 */
final class FakeWorkspaceDaemon implements InstanceRuntime, ExecSupport, VolumeSnapshotSupport {

    /** Handles the fake daemon knows about, and whether each is running. */
    private final Map<String, Boolean> workloads = new LinkedHashMap<>();

    /** Every script this daemon was asked to exec, in order. */
    final List<String> execs = new ArrayList<>();

    /** The options each exec ran with, in the order of {@link #execs}. */
    final List<ExecSupport.ExecOptions> execOptions = new ArrayList<>();

    /** Every spec this daemon was asked to CREATE a workload from, in order. */
    final List<InstanceSpec> created = new ArrayList<>();

    /**
     * Host preparation and container creation, in the order the funnel performed them.
     *
     * AIDEV-NOTE: the ordering is load-bearing, not decoration -- a bind whose source does
     * not exist when the container is created is silently created root-owned by the daemon,
     * so {@code prepareForDeploy} must land before every {@code create}.
     */
    final List<String> events = new ArrayList<>();

    /** The commit the scripted checkout lands on. */
    static final String COMMIT = "cafebabecafebabecafebabecafebabecafebabe";

    /**
     * What the next exec prints; the checkout reads its commit identity out of this.
     * The marker spelling matches {@code WorkspaceBuilds.COMMIT_MARKER}, which is
     * package-private there and already spelled this way by {@code WorkspaceKindTest}.
     */
    String execOutput = "hohenheim-commit:" + COMMIT + "\n";

    /** The exit code every exec that is not the checkout script answers with (the build command's). */
    int buildExitCode;

    /** Consumed by the next exec: the failure it throws after recording the command, as a run the runtime lost. */
    @Nullable IOException nextExecFailure;

    /** Every start fails, as a daemon that cannot bring the container back does. */
    boolean failStart;

    private boolean unreachable;

    /** Install this daemon behind the real workspace kind's identifier. */
    static FakeWorkspaceDaemon install() {
        FakeWorkspaceDaemon daemon = new FakeWorkspaceDaemon();
        InstanceKinds.replace(new FakeWorkspaceKind(daemon));
        return daemon;
    }

    /** Put the real kind back, so a later class in this JVM gets the production handler. */
    static void uninstall() {
        InstanceKinds.replace(new WorkspaceKind());
    }

    /**
     * A source-declaring workspace on {@code hostId}, with no port to publish.
     *
     * @param buildCommand the declared build command, or null for none
     */
    static int sourceWorkspace(@NonNull String name, int hostId, @Nullable String buildCommand) {
        RuntimeImageModel images = Models.get(RuntimeImageModel.class);
        Row image = images.findByName("push-policy-image");
        if (image == null) {
            image = images.createEmptyRow();
            image.set(RuntimeImageModel.NAME, "push-policy-image");
            image.set(RuntimeImageModel.INCUS_IMAGE, "hohenheim/node-22");
            image.set(RuntimeImageModel.DOCKER_IMAGE, "hohenheim/node-22:1");
            image.set(RuntimeImageModel.DEFAULT_COMMAND, "npm start");
            image.set(RuntimeImageModel.BUILD_CONTEXT, "images/node-22");
            image.set(RuntimeImageModel.ENABLED, true);
            images.save(image);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("repository_url", "https://git.example.test/team/app.git");
        settings.put("branch", "main");
        if (buildCommand != null) {
            settings.put(GitSourceSchema.BUILD_COMMAND, buildCommand);
        }
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, WorkspaceKind.ID.toString());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, image.get(RuntimeImageModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** The daemon answers nothing at all: UNREACHABLE, never ABSENT. */
    void setUnreachable(boolean unreachable) {
        this.unreachable = unreachable;
    }

    /** The workload's process exits, exactly as a start command that dies does. */
    void stop(@NonNull String handle) {
        this.workloads.computeIfPresent(handle, (key, running) -> false);
    }

    /** The workload is gone from the daemon entirely. */
    void remove(@NonNull String handle) {
        this.workloads.remove(handle);
    }

    boolean isRunning(@NonNull String handle) {
        return Boolean.TRUE.equals(this.workloads.get(handle));
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        requireReachable();
        this.created.add(spec);
        this.events.add("create:" + spec.handle());
        this.workloads.putIfAbsent(spec.handle(), false);
        return spec.handle();
    }

    /** The spec of the last container this daemon was asked to create. */
    @NonNull InstanceSpec lastCreated() {
        if (this.created.isEmpty()) {
            throw new IllegalStateException("no container was created");
        }
        return this.created.get(this.created.size() - 1);
    }

    @Override
    public void start(@NonNull String handle) throws IOException {
        requireReachable();
        if (this.failStart) {
            throw new IOException("scripted start failure");
        }
        this.workloads.put(handle, true);
    }

    @Override
    public void stop(@NonNull String handle, int graceSeconds) throws IOException {
        requireReachable();
        stop(handle);
    }

    @Override
    public void destroy(@NonNull String handle) throws IOException {
        requireReachable();
        remove(handle);
    }

    /** Every mutating call fails on an unreachable daemon; only status() answers. */
    private void requireReachable() throws IOException {
        if (this.unreachable) {
            throw new IOException("the fake daemon is unreachable");
        }
    }

    @Override
    public @NonNull InstanceStatus status(@NonNull String handle) {
        if (this.unreachable) {
            return new InstanceStatus(ContainerState.UNREACHABLE, null);
        }
        Boolean running = this.workloads.get(handle);
        if (running == null) {
            return new InstanceStatus(ContainerState.ABSENT, null);
        }
        return new InstanceStatus(running ? ContainerState.RUNNING : ContainerState.STOPPED,
            null);
    }

    @Override
    public ExecSupport.@NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                                    @NonNull List<String> command,
                                                    ExecSupport.@NonNull ExecOptions options,
                                                    long timeoutMs) throws IOException {
        requireReachable();
        this.execs.add(command.get(command.size() - 1));
        this.execOptions.add(options);
        IOException failure = this.nextExecFailure;
        this.nextExecFailure = null;
        if (failure != null) {
            throw failure;
        }
        // The checkout script is the one that prints the commit marker; anything else is the build command.
        boolean checkout = command.get(command.size() - 1).contains("hohenheim-commit:");
        return new ExecSupport.ExecOutcome(checkout ? 0 : this.buildExitCode,
            checkout ? this.execOutput : "build output\n");
    }

    /** A workspace declares no named volumes, so a capture only proves it ran while the workload was stopped. */
    @Override
    public @NonNull List<CapturedVolume> captureVolumes(@NonNull InstanceSpec spec,
                                                        @NonNull Map<String, String> logicalVolumes,
                                                        @NonNull Path directory,
                                                        long maxBytesPerVolume) throws IOException {
        if (status(spec.handle()).running()) {
            throw new IOException("capture attempted while the workload runs");
        }
        if (!logicalVolumes.isEmpty()) {
            throw new IOException("the fake workspace daemon does not model volume bytes");
        }
        this.events.add("capture:" + spec.handle());
        return List.of();
    }

    @Override
    public void restoreVolumes(@NonNull InstanceSpec spec, @NonNull Map<String, String> logicalVolumes,
                               @NonNull Map<String, Path> tars) throws IOException {
        throw new IOException("the fake workspace daemon does not restore volumes");
    }

    @Override
    public void removeVolumesForRestore(@NonNull InstanceSpec spec, @NonNull Map<String, String> logicalVolumes,
                                        @NonNull Collection<String> names) throws IOException {
        throw new IOException("the fake workspace daemon does not remove volumes");
    }

    @Override
    public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) {
        return new ImageIdentity(spec.image(), "sha256:" + "b".repeat(64));
    }

    /** The real workspace kind, delegating everything but its runtime and host prep. */
    private static final class FakeWorkspaceKind extends ForwardingKindHandler {

        private final FakeWorkspaceDaemon daemon;

        private FakeWorkspaceKind(FakeWorkspaceDaemon daemon) {
            super(new WorkspaceKind());
            this.daemon = daemon;
        }

        /** The one real swap: no daemon, no host, no filesystem. */
        @Override public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return this.daemon;
        }

        /** No host directories to hand over: carried files keep their owners, as on a kind that declares none. */
        @Override public @Nullable Integer volumeOwnerUid(int instanceId, @NonNull String serverName) {
            return null;
        }

        /**
         * Deliberately does no host work: the real one materializes host directories with a
         * quota and builds a runtime image on the host, both of which need a real machine.
         * It records that it RAN, because when it ran relative to create is the whole
         * ordering contract the mounts depend on.
         */
        @Override public void prepareForDeploy(int instanceId, @NonNull String serverName,
                                               @NonNull Map<String, Object> settings) {
            this.daemon.events.add("prepare:" + instanceId);
        }
    }
}
