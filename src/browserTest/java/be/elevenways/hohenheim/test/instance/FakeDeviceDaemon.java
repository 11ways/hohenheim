package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DeviceAttachSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory device-capable daemon behind a fake incus-runtime kind, so "did the volume really get created,
 * resized or deleted" is an assertion on the daemon rather than an inference from a row.
 *
 * AIDEV-NOTE: the InstanceMigrationTest shape, shared by the device surface journeys and the /api/v1 wire capture;
 * a workload must be put into {@link #DAEMON} under {@link #handleOf} before a device call, or the call is the
 * ABSENT lane.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class FakeDeviceDaemon {

    /** handle -> volumes and nics the fake daemon holds. */
    static final Map<String, Workload> DAEMON = new ConcurrentHashMap<>();

    /** Flipped to make the next daemon call refuse (the "row must not survive" lane). */
    static volatile boolean refuses;

    private FakeDeviceDaemon() {
    }

    /** @return the handle the fake kind spells for an instance id */
    static String handleOf(int instanceId) {
        return "devsurf-instance-" + instanceId;
    }

    static final class Workload {
        final Map<String, Integer> disks = new LinkedHashMap<>();
        final List<String> nics = new ArrayList<>();
        final Map<String, String> cdroms = new LinkedHashMap<>();
    }

    static final class Runtime
            implements InstanceRuntime, DeviceAttachSupport {

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) {
            DAEMON.computeIfAbsent(spec.handle(), handle -> new Workload());
            return spec.handle();
        }

        @Override public void start(@NonNull String handle) {}

        @Override public void stop(@NonNull String handle, int graceSeconds) {}

        @Override public void destroy(@NonNull String handle) { DAEMON.remove(handle); }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            return new InstanceStatus(DAEMON.containsKey(handle)
                ? ContainerState.RUNNING : ContainerState.ABSENT, null);
        }

        private @NonNull Workload require(InstanceSpec spec) throws IOException {
            if (refuses) {
                throw new IOException("the fake daemon refuses this device");
            }
            Workload workload = DAEMON.get(spec.handle());
            if (workload == null) {
                throw new IOException("no workload " + spec.handle());
            }
            return workload;
        }

        @Override
        public void ensureDisk(@NonNull InstanceSpec spec, @NonNull String name, int sizeGb)
                throws IOException {
            require(spec).disks.put(name, sizeGb);
        }

        @Override
        public Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            return require(spec).disks.get(name);
        }

        @Override
        public void resizeDisk(@NonNull InstanceSpec spec, @NonNull String name, int sizeGb)
                throws IOException {
            require(spec).disks.put(name, sizeGb);
        }

        @Override
        public void ensureNic(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            require(spec).nics.add(name);
        }

        @Override
        public void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String name,
                                @NonNull String mediaVolume) throws IOException {
            require(spec).cdroms.put(name, mediaVolume);
        }

        @Override
        public void removeDevice(@NonNull InstanceSpec spec, @NonNull String name,
                                 boolean disk) throws IOException {
            Workload workload = require(spec);
            if (disk) {
                workload.disks.remove(name);
            } else {
                workload.nics.remove(name);
                workload.cdroms.remove(name);
            }
        }

        @Override
        public void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> names)
                throws IOException {
            Workload workload = require(spec);
            names.forEach(workload.disks::remove);
        }
    }

    /** An incus-runtime kind whose driver DOES carry devices, with no daemon behind it. */
    static final class Kind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_device_capable");
        static final Schema SETTINGS_SCHEMA = new Schema();
        static final StringField IMAGE = SETTINGS_SCHEMA.addField(
            StringField.builder().name("image").build());

        static void register() {
            if (InstanceKinds.getHandler(ID.toString()) == null) {
                InstanceKinds.register(new Kind());
            }
        }

        @Override public @NonNull Identifier typeId() { return ID; }

        @Override public @NonNull String getDisplayName() { return "Fake device-capable"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_device_capable").withFilter("scope", "instance_kind");
        }

        @Override
        public @NonNull Microcopy getDescription() {
            return Microcopy.of("fake_device_capable").withFilter("scope", "instance_kind_description");
        }

        @Override public Icon getIcon() { return Icon.of("flask"); }

        @Override public BadgeColor color() { return ColorHue.GRAY; }

        @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override public @NonNull Set<String> supportedRuntimes() { return Set.of(ServerModel.RUNTIME_INCUS); }

        /** Its runtime really does implement DeviceAttachSupport, so it declares it. */
        @Override public boolean supportsDevices() { return true; }

        /** Cdrom journeys ride this kind too; the fake runtime honours ensureCdrom. */
        @Override public boolean supportsInstallMedia() { return true; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new Runtime();
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return InstanceSpec.builder(handleOf(instanceId),
                String.valueOf(settings.getOrDefault("image", "fake/image")),
                ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId)).build();
        }

        /** Test kinds declare a footprint like any other: the interface has no default. */
        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return 128;
        }
    }
}
