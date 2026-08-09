package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstallSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport.WorkloadClaim;
import be.elevenways.hohenheim.server.runtime.StatsStreamSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The shared in-memory "daemons" every daemon-free instance journey runs against: three
 * fake instance kinds over ONE workload store -- native snapshots + install steps
 * ({@link FakeNativeKind}), controller-side volume snapshots
 * ({@link FakeVolumeSnapshotKind}), and a driver that supports NEITHER
 * ({@link FakeVolumeKind}, the "refused by name" lane) -- whose workloads live in a
 * per-host-name map this class owns.
 *
 * AIDEV-NOTE: shared rather than copied. Kinds register into the ONE global InstanceKinds
 * registry, so two test classes each declaring their own `hohenheim:fake_native` would
 * collide in a shared fork -- the registration here is idempotent and there is exactly
 * one definition of what a fake workload is.
 */
final class FakeNativeDaemons {

    /** name -> handle -> workload; the fake daemons, keyed by HOST record name. */
    static final Map<String, Map<String, FakeWorkload>> DAEMONS = new ConcurrentHashMap<>();

    private FakeNativeDaemons() {
    }

    /** Register both fake kinds (idempotent) and make sure this host has a daemon map. */
    static void register() {
        FakeNativeKind.register();
    }

    /** The fake daemon of a host record, created on first use. */
    static Map<String, FakeWorkload> daemonOf(int serverId) {
        return DAEMONS.computeIfAbsent(ServerModel.nameOf(serverId),
            name -> new ConcurrentHashMap<>());
    }

    /** The handle the fake kinds spell for an instance id. */
    static String handleOf(int instanceId) {
        return "fake-instance-" + instanceId;
    }


    static final class FakeWorkload {
        final Map<String, String> data = new LinkedHashMap<>();
        final List<String> snapshots = new ArrayList<>();
        boolean running;
        Identifier ownerModel;
        String ownerId;
    }

    // -- the observability lanes ----------------------------------------------
    // AIDEV-NOTE: stats and console live HERE rather than in a second fake because they
    // are DRIVER CAPABILITIES of the same workload, exactly as they are on the real
    // drivers: the hub asks the resolved runtime whether it implements the lane, so a
    // separate harness would have to re-fake the whole runtime to be asked at all.
    // FakeVolumeKind deliberately keeps exposing neither, which is what makes "a driver
    // without the lane is refused BY NAME" testable.

    /** handle -> the stream {@code openStats} last handed out; the test pushes into it. */
    static final Map<String, ScriptedStream> STATS_STREAMS = new ConcurrentHashMap<>();

    /** handle -> the stream {@code openConsole} last handed out. */
    static final Map<String, ScriptedStream> CONSOLE_STREAMS = new ConcurrentHashMap<>();

    /** handles whose stats open must FAIL: a stats read that never answers. */
    static final Set<String> STATS_FAILS = ConcurrentHashMap.newKeySet();

    /** handles whose one-shot console tail must FAIL: the daemon cannot be asked. */
    static final Set<String> TAIL_FAILS = ConcurrentHashMap.newKeySet();

    /** handle -> what the one-shot console tail answers. */
    static final Map<String, String> TAILS = new ConcurrentHashMap<>();

    /**
     * Consumed by the next {@code createSnapshot}: a writer that appears while the
     * daemon is taking the capture, on the caller's thread and datasource scope. That
     * window is minutes long in production and the snapshot's note is editable through
     * the admin form for all of it.
     */
    static final AtomicReference<Runnable> DURING_SNAPSHOT = new AtomicReference<>();

    // -- the install lane -----------------------------------------------------

    /**
     * handle -> every install script this daemon ran there, in order. The install
     * workload is a SIBLING of the instance's own: it runs whether or not the workload
     * itself still exists, which is precisely what a clear reinstall depends on.
     */
    static final Map<String, List<String>> INSTALL_RUNS = new ConcurrentHashMap<>();

    /** handle -> the environment the last install run received (the instance variables). */
    static final Map<String, Map<String, String>> INSTALL_ENV = new ConcurrentHashMap<>();

    /** Handles whose install script must EXIT NON-ZERO: a failing install step. */
    static final Set<String> INSTALL_FAILS = ConcurrentHashMap.newKeySet();

    /** Forget every recorded install run; call between journeys. */
    static void resetInstalls() {
        INSTALL_RUNS.clear();
        INSTALL_ENV.clear();
        INSTALL_FAILS.clear();
    }

    /** Forget every scripted stream and failure; call between journeys. */
    static void resetStreams() {
        STATS_STREAMS.values().forEach(ScriptedStream::close);
        CONSOLE_STREAMS.values().forEach(ScriptedStream::close);
        STATS_STREAMS.clear();
        CONSOLE_STREAMS.clear();
        STATS_FAILS.clear();
        TAIL_FAILS.clear();
        TAILS.clear();
    }

    /**
     * A driver stream whose frames the TEST writes: {@link #push} delivers one chunk to
     * whoever is blocked in {@link #next()}, and {@link #close()} unblocks it the way the
     * real streams do.
     */
    static final class ScriptedStream implements ConsoleStream {

        private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        private final List<String> stdinWrites = new ArrayList<>();
        private volatile boolean closed;

        /** Deliver one output frame verbatim, chunk boundaries included. */
        void push(@NonNull String text) {
            this.frames.add(text.getBytes(StandardCharsets.UTF_8));
        }

        boolean isClosed() {
            return this.closed;
        }

        @Override
        public ConsoleStream.@Nullable Chunk next() {
            while (!this.closed) {
                byte[] frame;
                try {
                    frame = this.frames.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (frame == null) {
                    continue;
                }
                if (frame.length == 0) {
                    return null;   // endFromDaemon / loseDaemon
                }
                return new ConsoleStream.Chunk(false, frame);
            }
            return null;
        }

        @Override
        public void writeStdin(byte @NonNull [] data) throws IOException {
            if (this.closed) {
                throw new IOException("stream closed");
            }
            this.stdinWrites.add(new String(data, StandardCharsets.UTF_8));
        }

        /**
         * Always CONSUMER_CLOSED: nothing here asserts the crash-detection policy that
         * ENDED and DAEMON_LOST drive, and a termination no test exercises would be a
         * fake answering a question nobody asked.
         */
        @Override
        public ConsoleStream.@NonNull Termination termination() {
            return ConsoleStream.Termination.CONSUMER_CLOSED;
        }

        @Override
        public @NonNull String detail() {
            return "";
        }

        @Override
        public void close() {
            this.closed = true;
            this.frames.add(new byte[0]);
        }
    }

    static final class FakeNativeRuntime
            implements InstanceRuntime, NativeSnapshotSupport, StatsStreamSupport,
                       ConsoleStreamSupport, InstallSupport {

        private final Map<String, FakeWorkload> daemon;

        FakeNativeRuntime(String serverName) {
            this.daemon = DAEMONS.computeIfAbsent(serverName,
                name -> new ConcurrentHashMap<>());
        }

        private static OwnerLabels.Owner ownerOf(InstanceSpec spec) throws IOException {
            OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
            if (owner == null) {
                throw new IOException("spec without owner labels");
            }
            return owner;
        }

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
            OwnerLabels.Owner owner = ownerOf(spec);
            FakeWorkload existing = this.daemon.get(spec.handle());
            if (existing != null) {
                if (!owner.model().equals(existing.ownerModel)
                        || !owner.id().equals(existing.ownerId)) {
                    throw new IOException("REFUSED: foreign workload under " + spec.handle());
                }
                return spec.handle();   // converge keeps state
            }
            FakeWorkload workload = new FakeWorkload();
            workload.ownerModel = owner.model();
            workload.ownerId = owner.id();
            this.daemon.put(spec.handle(), workload);
            return spec.handle();
        }

        @Override
        public void start(@NonNull String handle) throws IOException {
            require(handle).running = true;
        }

        @Override
        public void stop(@NonNull String handle, int graceSeconds) throws IOException {
            require(handle).running = false;
        }

        @Override
        public void destroy(@NonNull String handle) {
            this.daemon.remove(handle);
        }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            FakeWorkload workload = this.daemon.get(handle);
            if (workload == null) {
                return new InstanceStatus(ContainerState.ABSENT, null);
            }
            return new InstanceStatus(workload.running
                ? ContainerState.RUNNING : ContainerState.STOPPED, null);
        }

        private @NonNull FakeWorkload require(String handle) throws IOException {
            FakeWorkload workload = this.daemon.get(handle);
            if (workload == null) {
                throw new IOException("no workload " + handle);
            }
            return workload;
        }

        // -- NativeSnapshotSupport --------------------------------------------

        @Override
        public void createSnapshot(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            Runnable during = DURING_SNAPSHOT.getAndSet(null);
            if (during != null) {
                during.run();
            }
            require(spec.handle()).snapshots.add(name);
        }

        @Override
        public boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            return require(spec.handle()).snapshots.contains(name);
        }

        @Override
        public void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String name) {
        }

        @Override
        public void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            require(spec.handle()).snapshots.remove(name);
        }

        @Override
        public long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination,
                                 long maxBytes, boolean withSnapshots) throws IOException {
            FakeWorkload workload = require(spec.handle());
            StringBuilder text = new StringBuilder();
            workload.data.forEach((key, value) ->
                text.append("data ").append(key).append('=').append(value).append('\n'));
            if (withSnapshots) {
                workload.snapshots.forEach(snapshot ->
                    text.append("snapshot ").append(snapshot).append('\n'));
            }
            Files.writeString(destination, text.toString());
            return Files.size(destination);
        }

        @Override
        public void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive)
                throws IOException {
            if (this.daemon.containsKey(spec.handle())) {
                throw new IOException("already exists: " + spec.handle());
            }
            OwnerLabels.Owner owner = ownerOf(spec);
            FakeWorkload workload = new FakeWorkload();
            workload.ownerModel = owner.model();
            workload.ownerId = owner.id();
            for (String line : Files.readAllLines(archive)) {
                if (line.startsWith("data ")) {
                    String[] pair = line.substring(5).split("=", 2);
                    workload.data.put(pair[0], pair.length > 1 ? pair[1] : "");
                } else if (line.startsWith("snapshot ")) {
                    workload.snapshots.add(line.substring(9));
                }
            }
            this.daemon.put(spec.handle(), workload);
        }

        @Override
        public @NonNull WorkloadClaim claimOf(@NonNull InstanceSpec spec) throws IOException {
            FakeWorkload workload = this.daemon.get(spec.handle());
            if (workload == null) {
                return WorkloadClaim.ABSENT;
            }
            OwnerLabels.Owner owner = ownerOf(spec);
            return owner.model().equals(workload.ownerModel)
                && owner.id().equals(workload.ownerId)
                ? WorkloadClaim.OURS : WorkloadClaim.FOREIGN;
        }

        @Override
        public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) {
            return new ImageIdentity(spec.image(), "fake-fingerprint");
        }

        // -- InstallSupport ---------------------------------------------------

        /**
         * Run the template's install script in a sibling that shares the instance's
         * volumes. The write into {@code data} IS the script's effect on those volumes,
         * which is what makes "the clear policy wiped them" observable: a destroyed
         * workload took its data with it, so the re-run starts from nothing.
         */
        @Override
        public @NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec,
                                                  @NonNull String installImage,
                                                  @NonNull String script,
                                                  @NonNull Map<String, String> env,
                                                  long timeoutMs) {
            String handle = spec.handle();
            INSTALL_RUNS.computeIfAbsent(handle, key -> new ArrayList<>()).add(script);
            INSTALL_ENV.put(handle, new LinkedHashMap<>(env));
            if (INSTALL_FAILS.contains(handle)) {
                return new InstallOutcome(1, "install script failed");
            }
            FakeWorkload workload = this.daemon.get(handle);
            if (workload != null) {
                workload.data.put("installed", script);
            }
            return new InstallOutcome(0, "install script ok");
        }

        // -- StatsStreamSupport -----------------------------------------------

        @Override
        public @NonNull ConsoleStream openStats(@NonNull String handle) throws IOException {
            if (STATS_FAILS.contains(handle)) {
                throw new IOException("the stats stream of " + handle + " did not answer");
            }
            requireRunning(handle);
            ScriptedStream stream = new ScriptedStream();
            STATS_STREAMS.put(handle, stream);
            return stream;
        }

        // -- ConsoleStreamSupport ---------------------------------------------

        @Override
        public @NonNull Console openConsole(@NonNull String handle) throws IOException {
            require(handle);
            ScriptedStream stream = new ScriptedStream();
            CONSOLE_STREAMS.put(handle, stream);
            return new Console(stream, true);
        }

        @Override
        public @NonNull String consoleTail(@NonNull String handle, int lines)
                throws IOException {
            if (TAIL_FAILS.contains(handle)) {
                throw new IOException("the console of " + handle + " cannot be read");
            }
            return TAILS.getOrDefault(handle, "");
        }

        @Override
        public @Nullable Integer exitCode(@NonNull String handle) throws IOException {
            return require(handle).running ? null : 0;
        }

        private @NonNull FakeWorkload requireRunning(@NonNull String handle)
                throws IOException {
            FakeWorkload workload = require(handle);
            if (!workload.running) {
                throw new IOException("workload " + handle + " is not running");
            }
            return workload;
        }
    }

    /** The migratable fake kind: native export/import over the in-memory daemons. */
    static final class FakeNativeKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_native");
        static final Schema SETTINGS_SCHEMA = new Schema();
        static final StringField IMAGE = SETTINGS_SCHEMA.addField(
            StringField.builder().name("image").build());
        private static boolean registered;

        static void register() {
            if (!registered) {
                registered = true;
                InstanceKinds.register(new FakeNativeKind());
                InstanceKinds.register(new FakeVolumeKind());
                InstanceKinds.register(new FakeVolumeSnapshotKind());
            }
        }

        @Override
        public @NonNull Identifier typeId() { return ID; }

        @Override
        public @NonNull String getDisplayName() { return "Fake native"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_native").withFilter("scope", "instance_kind");
        }

        @Override
        public String getDescription() { return "in-memory native test kind"; }

        @Override
        public Icon getIcon() { return Icon.of("flask"); }

        @Override
        public String getColor() { return "gray"; }

        @Override
        public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override
        public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new FakeNativeRuntime(serverName);
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            // A `container_port` setting declares a PUBLIC publication, exactly as the
            // real port-carrying kinds do. This is the only way a MIGRATABLE (native
            // export/import) spec can carry one: both Incus kinds are structurally
            // publication-free, so the migration refusal would be untestable without it.
            Object port = settings.get("container_port");
            PortPublication publication = port instanceof Number number
                ? new PortPublication(number.intValue(), PortPublication.TCP, true, null, null)
                : null;
            return new InstanceSpec("fake-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")), null,
                Map.of(), Map.of(), publication, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
        }

        /** Test kinds declare a footprint like any other: the interface has no default. */
        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }
    }

    /**
     * The VOLUME snapshot lane's kind: {@link VolumeSnapshotSupport} and deliberately NOT
     * {@link NativeSnapshotSupport}, so {@code InstanceSnapshots} takes its controller-side
     * branch -- real tar files under the snapshot root, which is the only lane where a
     * prune has a filesystem payload to fail on.
     */
    static final class FakeVolumeSnapshotKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_volume_snapshot");

        @Override
        public @NonNull Identifier typeId() { return ID; }

        @Override
        public @NonNull String getDisplayName() { return "Fake volume snapshot"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_volume_snapshot").withFilter("scope", "instance_kind");
        }

        @Override
        public String getDescription() { return "in-memory volume-snapshot test kind"; }

        @Override
        public Icon getIcon() { return Icon.of("flask"); }

        @Override
        public String getColor() { return "gray"; }

        @Override
        public Schema getSchema() { return FakeNativeKind.SETTINGS_SCHEMA; }

        @Override
        public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new FakeVolumeSnapshotRuntime(serverName);
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return new InstanceSpec("fake-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")), null,
                Map.of(), Map.of(), null, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
        }

        /** Test kinds declare a footprint like any other: the interface has no default. */
        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }
    }

    /**
     * The volume lane's runtime: the same in-memory daemon, with the capture/restore half
     * of the driver seam writing REAL files, because the payload the prune must remove is
     * a real directory on the controller.
     */
    static final class FakeVolumeSnapshotRuntime
            implements InstanceRuntime, VolumeSnapshotSupport {

        private final FakeNativeRuntime inner;

        FakeVolumeSnapshotRuntime(String serverName) {
            this.inner = new FakeNativeRuntime(serverName);
        }

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
            return this.inner.create(spec);
        }

        @Override
        public void start(@NonNull String handle) throws IOException {
            this.inner.start(handle);
        }

        @Override
        public void stop(@NonNull String handle, int graceSeconds) throws IOException {
            this.inner.stop(handle, graceSeconds);
        }

        @Override
        public void destroy(@NonNull String handle) throws IOException {
            this.inner.destroy(handle);
        }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            return this.inner.status(handle);
        }

        @Override
        public @NonNull List<CapturedVolume> captureVolumes(
                @NonNull InstanceSpec spec, @NonNull Map<String, String> logicalVolumes,
                @NonNull Path directory, long maxBytesPerVolume) throws IOException {
            FakeWorkload workload = this.inner.require(spec.handle());
            List<CapturedVolume> captured = new ArrayList<>();
            for (Map.Entry<String, String> volume : logicalVolumes.entrySet()) {
                Path file = directory.resolve(volume.getKey() + ".tar");
                Files.writeString(file, String.valueOf(workload.data));
                captured.add(new CapturedVolume(volume.getKey(), volume.getValue(),
                    file, Files.size(file)));
            }
            return captured;
        }

        @Override
        public void restoreVolumes(@NonNull InstanceSpec spec,
                                   @NonNull Map<String, String> logicalVolumes,
                                   @NonNull Map<String, Path> tars) throws IOException {
            this.inner.require(spec.handle()).data.put("restored", String.valueOf(tars.keySet()));
        }

        @Override
        public void removeVolumesForRestore(@NonNull InstanceSpec spec,
                                            @NonNull Map<String, String> logicalVolumes,
                                            @NonNull Collection<String> names)
                throws IOException {
            this.inner.require(spec.handle()).data.keySet().removeAll(names);
        }

        @Override
        public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) {
            return new ImageIdentity(spec.image(), "fake-fingerprint");
        }
    }

    /** A kind whose runtime has NO native export/import: the migrate_unsupported lane. */
    static final class FakeVolumeKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_volume_only");

        static void register() {
            FakeNativeKind.register();
        }

        @Override
        public @NonNull Identifier typeId() { return ID; }

        @Override
        public @NonNull String getDisplayName() { return "Fake volume-only"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_volume_only").withFilter("scope", "instance_kind");
        }

        @Override
        public String getDescription() { return "in-memory non-native test kind"; }

        @Override
        public Icon getIcon() { return Icon.of("flask"); }

        @Override
        public String getColor() { return "gray"; }

        @Override
        public Schema getSchema() { return FakeNativeKind.SETTINGS_SCHEMA; }

        @Override
        public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            // Same daemon map, but WITHOUT NativeSnapshotSupport on the type.
            FakeNativeRuntime inner = new FakeNativeRuntime(serverName);
            return new InstanceRuntime() {
                @Override
                public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
                    return inner.create(spec);
                }

                @Override
                public void start(@NonNull String handle) throws IOException {
                    inner.start(handle);
                }

                @Override
                public void stop(@NonNull String handle, int graceSeconds)
                        throws IOException {
                    inner.stop(handle, graceSeconds);
                }

                @Override
                public void destroy(@NonNull String handle) throws IOException {
                    inner.destroy(handle);
                }

                @Override
                public @NonNull InstanceStatus status(@NonNull String handle) {
                    return inner.status(handle);
                }
            };
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return new InstanceSpec("fake-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")), null,
                Map.of(), Map.of(), null, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
        }

        /** Test kinds declare a footprint like any other: the interface has no default. */
        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }
    }
}
