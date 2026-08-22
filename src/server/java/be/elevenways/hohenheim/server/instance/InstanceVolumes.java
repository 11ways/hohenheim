package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.host.VolumeOperations;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE volume mechanism of the instance tier: Hohenheim-owned host directories under
 * {@code <data_path>/volumes/<instance id>/<name>}, bind-mounted into the workload.
 *
 * AIDEV-NOTE: volumes are keyed to the instance the OPERATOR authors, never to the
 * container that happens to be running. That is the whole reason the deleted
 * {@code SiteVolumes} existed: a gated release mints a NEW release instance row, so a
 * volume derived from the running container's identity is EMPTY on every deploy and the
 * old one is orphaned with the data in it. An application's release containers therefore
 * mount the APPLICATION's volumes, which is what {@link #mountsFor} takes an owner id for.
 *
 * AIDEV-NOTE: the host directory is created through the host's detected {@link
 * VolumeBackend}, not with mkdir. A btrfs host gets a subvolume (quota + snapshot); a host
 * whose backend cannot do it refuses BY NAME. That refusal is the point -- a volume with a
 * quota nothing enforces looks identical to one with a quota until it fills the disk.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceVolumes {

    private InstanceVolumes() {
    }

    /** The directory that holds every volume of one instance. */
    public static @NonNull String rootFor(int instanceId) {
        return VolumeBackends.volumeRoot() + "/" + instanceId;
    }

    /**
     * The host directory of one declared volume.
     *
     * @throws Violations when the name is not a plain path segment -- the derivation IS the
     *         containment guarantee, so a name carrying a slash or a {@code ..} would put
     *         the mount outside the volume root that {@code ContainerHardening} checks
     */
    public static @NonNull String hostPathFor(int instanceId, @NonNull String name) {
        requirePlainName(name);
        return rootFor(instanceId) + "/" + name;
    }

    /** This instance's declared volumes, name-ordered. */
    public static @NonNull List<Row> declaredFor(int instanceId) {
        return Models.get(InstanceVolumeModel.class).findByInstanceId(instanceId);
    }

    /** @return whether any declared volume forbids two workloads holding it at once */
    public static boolean hasExclusive(int instanceId) {
        for (Row volume : declaredFor(instanceId)) {
            if (Boolean.TRUE.equals(volume.get(InstanceVolumeModel.EXCLUSIVE))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Declare a volume on an instance, or update the declaration that is already there.
     *
     * @return the stored row
     */
    public static @NonNull Row declare(int instanceId, @NonNull String name,
                                       @NonNull String containerPath, @Nullable Long quotaBytes,
                                       boolean exclusive) {
        requirePlainName(name);
        InstanceVolumeModel model = Models.get(InstanceVolumeModel.class);
        Row volume = model.find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceVolumeModel.NAME.eq(name))
            .first();
        if (volume == null) {
            volume = model.createEmptyRow();
            volume.set(InstanceVolumeModel.INSTANCE_ID, instanceId);
            volume.set(InstanceVolumeModel.NAME, name);
        }
        volume.set(InstanceVolumeModel.CONTAINER_PATH, containerPath);
        volume.set(InstanceVolumeModel.QUOTA_BYTES, quotaBytes);
        volume.set(InstanceVolumeModel.EXCLUSIVE, exclusive);
        volume.set(InstanceVolumeModel.HOST_PATH, hostPathFor(instanceId, name));
        model.save(volume);
        return volume;
    }

    /**
     * Create every declared volume of an owner on its host, apply the quotas, and hand back
     * the bind mounts a spec carries.
     *
     * @return host path -&gt; container path, in declaration order
     * @throws Violations naming the backend when the host cannot deliver a volume
     */
    public static @NonNull Map<String, String> mountsFor(int ownerInstanceId,
                                                         @NonNull String serverName) {
        return mountsFor(ownerInstanceId, serverName, null);
    }

    /**
     * {@link #mountsFor(int, String)}, giving every created directory to a uid.
     *
     * @param ownerUid the host uid the workload runs as, or null to leave the directory
     *                 owned by the controller -- a workspace passes its derived uid, so
     *                 its home is writable by the only identity that ever runs in it
     */
    public static @NonNull Map<String, String> mountsFor(int ownerInstanceId,
                                                         @NonNull String serverName,
                                                         @Nullable Integer ownerUid) {

        List<Row> declared = declaredFor(ownerInstanceId);
        Map<String, String> mounts = new LinkedHashMap<>();

        if (declared.isEmpty()) {
            return mounts;
        }

        Row server = requireServer(serverName);
        VolumeOperations operations = operationsFor(server);

        for (Row volume : declared) {
            String name = volume.get(InstanceVolumeModel.NAME);
            String containerPath = volume.get(InstanceVolumeModel.CONTAINER_PATH);
            if (name == null || containerPath == null || containerPath.isBlank()) {
                continue;
            }
            String hostPath = hostPathFor(ownerInstanceId, name);
            operations.create(hostPath);
            Long quota = volume.get(InstanceVolumeModel.QUOTA_BYTES);
            if (quota != null && quota > 0) {
                operations.setQuota(hostPath, quota);
            }
            // The stored host path is EVIDENCE (see InstanceVolumeModel): re-stamp it so a
            // changed data_path shows up as a rewritten row rather than as a mount nobody
            // can find the directory of.
            if (!hostPath.equals(volume.get(InstanceVolumeModel.HOST_PATH))) {
                volume.set(InstanceVolumeModel.HOST_PATH, hostPath);
                Models.get(InstanceVolumeModel.class).save(volume);
            }
            if (ownerUid != null) {
                operations.own(hostPath, ownerUid);
            }
            mounts.put(hostPath, containerPath);
        }

        return mounts;
    }

    /**
     * Take a pre-deploy snapshot of every declared volume.
     *
     * @return the snapshot host paths
     * @throws Violations when the owner declares volumes and the host cannot snapshot --
     *         a deploy that believes it snapshotted is worse than one that refused
     */
    public static @NonNull List<String> snapshotAll(int ownerInstanceId,
                                                    @NonNull String serverName,
                                                    @NonNull String label) {

        List<Row> declared = declaredFor(ownerInstanceId);
        List<String> snapshots = new ArrayList<>();

        if (declared.isEmpty()) {
            return snapshots;
        }

        Row server = requireServer(serverName);
        VolumeBackend backend = ServerModel.volumeBackendOf(server);

        if (!backend.supportsSnapshot()) {
            throw Violations.ofForm(Microcopy.of("volume_no_snapshot_support")
                .withFilter("scope", "violations")
                .withArg("name", serverName)
                .withArg("backend", backend.label()));
        }

        VolumeOperations operations = VolumeOperations.forBackend(backend,
            HostShell.forServer(server));

        for (Row volume : declared) {
            String name = volume.get(InstanceVolumeModel.NAME);
            if (name != null) {
                snapshots.add(operations.snapshot(hostPathFor(ownerInstanceId, name), label));
            }
        }

        return snapshots;
    }

    /** Record what each volume currently occupies; never throws, the number is advisory. */
    public static void refreshUsage(int ownerInstanceId, @NonNull String serverName) {
        try {
            Row server = requireServer(serverName);
            VolumeOperations operations = operationsFor(server);
            for (Row volume : declaredFor(ownerInstanceId)) {
                String name = volume.get(InstanceVolumeModel.NAME);
                if (name == null) {
                    continue;
                }
                long used = operations.usage(hostPathFor(ownerInstanceId, name));
                if (used < 0) {
                    continue;
                }
                volume.set(InstanceVolumeModel.USED_BYTES, used);
                volume.set(InstanceVolumeModel.OBSERVED_AT, Instant.now());
                Models.get(InstanceVolumeModel.class).save(volume);
            }
        } catch (RuntimeException unreadable) {
            Blast.log("VOLUMES: could not refresh usage of instance", ownerInstanceId,
                "-", unreadable.getMessage());
        }
    }

    /**
     * Destroy every declared volume of an instance and forget the declarations.
     *
     * AIDEV-NOTE: the ONE irreversible verb of this tier, and it exists only because a
     * consumer asked for it by name (the workspace's "delete data" destroy, phase-0 brief
     * 8). It is never reached by an ordinary destroy: {@code InstanceService.destroy}
     * keeps volumes, because a soft-deleted record's data must survive the record.
     *
     * @return the host paths that were removed
     */
    public static @NonNull List<String> destroyAll(int ownerInstanceId,
                                                   @NonNull String serverName) {

        List<Row> declared = declaredFor(ownerInstanceId);
        List<String> removed = new ArrayList<>();

        if (declared.isEmpty()) {
            return removed;
        }

        Row server = requireServer(serverName);
        VolumeOperations operations = operationsFor(server);
        InstanceVolumeModel model = Models.get(InstanceVolumeModel.class);

        for (Row volume : declared) {
            String name = volume.get(InstanceVolumeModel.NAME);
            if (name == null) {
                continue;
            }
            String hostPath = hostPathFor(ownerInstanceId, name);
            operations.destroy(hostPath);
            removed.add(hostPath);
        }

        model.find().where(InstanceVolumeModel.INSTANCE_ID.eq(ownerInstanceId)).delete();

        return removed;
    }

    /**
     * AIDEV-NOTE: an ordinary destroy deliberately keeps volumes, and that is a decision,
     * not a gap.
     * An application delete is a SOFT delete (deleted_at), so its data must survive it --
     * the same rule the site tier already applied to its volumes, and for the same reason:
     * a volume is unrecoverable, so removing one is a human act with the data still there.
     * A volume of a soft-deleted owner surfaces through the reconciler as an orphan.
     * {@link #destroyOne} is that human act's mechanism (the Volumes tab's typed-confirm
     * delete, phase-0 brief 9).
     */

    /**
     * Destroy ONE declared volume: directory removed at the daemon, then the row --
     * daemon first, so a failed removal keeps the declaration as evidence.
     *
     * @return the removed host path
     * @throws Violations naming the backend when the host cannot remove it
     */
    public static @NonNull String destroyOne(int ownerInstanceId, @NonNull String name,
                                             @NonNull String serverName) {
        InstanceVolumeModel model = Models.get(InstanceVolumeModel.class);
        Row volume = model.find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(ownerInstanceId))
            .where(InstanceVolumeModel.NAME.eq(name))
            .first();
        if (volume == null) {
            throw Violations.ofForm(Microcopy.of("volume_unknown")
                .withFilter("scope", "violations").withArg("name", name));
        }
        Row server = requireServer(serverName);
        String hostPath = hostPathFor(ownerInstanceId, name);
        operationsFor(server).destroy(hostPath);
        model.find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(ownerInstanceId))
            .where(InstanceVolumeModel.NAME.eq(name))
            .delete();
        return hostPath;
    }

    // -- internals -------------------------------------------------------------

    private static @NonNull VolumeOperations operationsFor(@NonNull Row server) {
        return VolumeOperations.forBackend(ServerModel.volumeBackendOf(server),
            HostShell.forServer(server));
    }

    private static @NonNull Row requireServer(@NonNull String serverName) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            throw Violations.ofForm(Microcopy.of("volume_host_unknown")
                .withFilter("scope", "violations").withArg("name", serverName));
        }
        return server;
    }

    /**
     * A volume name is a single path segment, and nothing else.
     *
     * @throws Violations {@code volume_name_invalid}
     */
    private static void requirePlainName(@NonNull String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..") || name.startsWith("-")) {
            throw Violations.ofField(InstanceVolumeModel.NAME.getName(), name,
                Microcopy.of("volume_name_invalid").withFilter("scope", "violations")
                    .withArg("name", name));
        }
    }
}
