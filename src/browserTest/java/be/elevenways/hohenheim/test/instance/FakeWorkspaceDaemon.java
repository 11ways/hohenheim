package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
final class FakeWorkspaceDaemon implements InstanceRuntime, ExecSupport {

    /** Handles the fake daemon knows about, and whether each is running. */
    private final Map<String, Boolean> workloads = new LinkedHashMap<>();

    /** Every script this daemon was asked to exec, in order. */
    final List<String> execs = new ArrayList<>();

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

    private boolean unreachable;

    /** Install this daemon behind the real workspace kind's identifier. */
    static FakeWorkspaceDaemon install() {
        FakeWorkspaceDaemon daemon = new FakeWorkspaceDaemon();
        InstanceKinds.register(new FakeWorkspaceKind(daemon));
        return daemon;
    }

    /** Put the real kind back, so a later class in this JVM gets the production handler. */
    static void uninstall() {
        InstanceKinds.register(new WorkspaceKind());
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
        return new ExecSupport.ExecOutcome(0, this.execOutput);
    }

    /** The real workspace kind, delegating everything but its runtime and host prep. */
    private static final class FakeWorkspaceKind implements InstanceKindHandler {

        private final WorkspaceKind real = new WorkspaceKind();
        private final FakeWorkspaceDaemon daemon;

        private FakeWorkspaceKind(FakeWorkspaceDaemon daemon) {
            this.daemon = daemon;
        }

        @Override public @NonNull Identifier typeId() { return WorkspaceKind.ID; }
        @Override public @NonNull String getDisplayName() { return this.real.getDisplayName(); }
        @Override public @NonNull Microcopy getLabel() { return this.real.getLabel(); }
        @Override public @NonNull Microcopy getDescription() { return this.real.getDescription(); }
        @Override public Icon getIcon() { return this.real.getIcon(); }
        @Override public String getColor() { return this.real.getColor(); }
        @Override public Schema getSchema() { return this.real.getSchema(); }
        @Override public @NonNull Set<String> supportedRuntimes() {
            return this.real.supportedRuntimes();
        }
        @Override public boolean supportsVolumes() { return this.real.supportsVolumes(); }
        @Override public boolean usesRuntimeImage() { return this.real.usesRuntimeImage(); }
        @Override public boolean requiresRuntimeImage() {
            return this.real.requiresRuntimeImage();
        }
        @Override public boolean supportsSiteUpstream() {
            return this.real.supportsSiteUpstream();
        }
        @Override public @NonNull WorkloadIsolation isolation() { return this.real.isolation(); }
        @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return this.real.defaultFootprintMb(settings);
        }
        @Override public @NonNull InstanceSpec specFor(int instanceId,
                                                       @NonNull Map<String, Object> settings) {
            return this.real.specFor(instanceId, settings);
        }

        /** The one real swap: no daemon, no host, no filesystem. */
        @Override public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return this.daemon;
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
