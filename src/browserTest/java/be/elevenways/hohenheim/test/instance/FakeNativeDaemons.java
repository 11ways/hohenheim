package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport.WorkloadClaim;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared in-memory "daemons" every daemon-free instance journey runs against: two
 * fake instance kinds (one WITH NativeSnapshotSupport, one deliberately without) whose
 * workloads live in a per-host-name map this class owns.
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

    static final class FakeNativeRuntime
            implements InstanceRuntime, NativeSnapshotSupport {

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
