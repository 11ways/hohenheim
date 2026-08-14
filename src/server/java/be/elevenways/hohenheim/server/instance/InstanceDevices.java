package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DeviceAttachSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Attach, resize and detach instance devices (extra disks and NICs) as DESIRED-STATE
 * rows reconciled onto the daemon: the row write charges the reservation ledger
 * adjacent to it (InstanceDeviceQuota), the daemon work follows, and a daemon refusal
 * reverts the row so the ledger never counts what the daemon does not carry. An
 * ABSENT workload takes the row alone -- deploy's reconcile materializes it, the same
 * desired-state contract as config files.
 *
 * Capability honesty: a runtime without {@link DeviceAttachSupport} refuses BY NAME
 * ({@code devices_unsupported}); nothing is stored for a driver that could never
 * honour it.
 */
public final class InstanceDevices {

    private final @NonNull InstanceService instances;

    public InstanceDevices() {
        this(new InstanceService());
    }

    public InstanceDevices(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * Attach a data disk of {@code sizeGb} under quota.
     *
     * @throws Violations {@code devices_unsupported}, {@code device_exists},
     *         {@code disk_quota_reached}, {@code device_attach_failed}, plus the
     *         model's name/size invariants
     */
    public void attachDisk(int instanceId, @NonNull String name, int sizeGb) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());
        requireAbsentRow(instanceId, name);

        Row row = Models.get(InstanceDeviceModel.class).createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        row.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_DISK);
        row.set(InstanceDeviceModel.NAME, name);
        row.set(InstanceDeviceModel.SIZE_GB, sizeGb);
        // The reservation fires HERE (beforeWrite, adjacent to the write): a full
        // bucket refuses before any daemon contact.
        Models.get(InstanceDeviceModel.class).save(row);

        if (workloadAbsent(resolved)) {
            return;   // desired state recorded; deploy reconciles it onto the daemon
        }
        try {
            support.ensureDisk(resolved.spec(), name, sizeGb);
        } catch (IOException e) {
            // The daemon does not carry it, so the ledger must not count it.
            Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
            throw refusal("device_attach_failed", resolved.row(), name, e);
        }
    }

    /**
     * Resize a data disk under quota (the DELTA is what the ledger judges).
     *
     * @throws Violations {@code device_not_found}, {@code disk_quota_reached},
     *         {@code device_resize_failed} -- the latter carrying the daemon's own
     *         refusal verbatim (notably "In use": block volumes resize stopped only)
     */
    public void resizeDisk(int instanceId, @NonNull String name, int sizeGb) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());

        Row row = rowOf(instanceId, name);
        if (row == null || !InstanceDeviceModel.TYPE_DISK.equals(row.get(InstanceDeviceModel.TYPE))) {
            throw Violations.ofField("name", name, violationText("device_not_found")
                .withArg("device", name));
        }
        Integer before = row.get(InstanceDeviceModel.SIZE_GB);
        row.set(InstanceDeviceModel.SIZE_GB, sizeGb);
        Models.get(InstanceDeviceModel.class).save(row);   // delta reserved/released here

        Integer daemonSize;
        try {
            daemonSize = support.diskSizeGb(resolved.spec(), name);
        } catch (IOException e) {
            revertSize(row, before);
            throw refusal("device_resize_failed", resolved.row(), name, e);
        }
        if (daemonSize == null) {
            return;   // volume not materialized yet; deploy's reconcile creates it at size
        }
        try {
            support.resizeDisk(resolved.spec(), name, sizeGb);
        } catch (IOException e) {
            revertSize(row, before);
            throw refusal("device_resize_failed", resolved.row(), name, e);
        }
    }

    /**
     * Attach an extra NIC under quota, isolation ACL enforced and verified.
     *
     * @throws Violations {@code devices_unsupported}, {@code device_exists},
     *         {@code nic_quota_reached}, {@code device_attach_failed}
     */
    public void attachNic(int instanceId, @NonNull String name) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());
        requireAbsentRow(instanceId, name);

        Row row = Models.get(InstanceDeviceModel.class).createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        row.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_NIC);
        row.set(InstanceDeviceModel.NAME, name);
        Models.get(InstanceDeviceModel.class).save(row);

        if (workloadAbsent(resolved)) {
            return;
        }
        try {
            support.ensureNic(resolved.spec(), name);
        } catch (IOException e) {
            Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
            throw refusal("device_attach_failed", resolved.row(), name, e);
        }
    }

    /**
     * Attach an operator-published install-media ISO as a CD-ROM device. OPERATOR-ONLY:
     * media provenance is arbitrary bootable code, so a tenant delegate -- CONFIG
     * included -- is refused with the tier's uniform refusal. Charges no quota; the
     * driver owns the boot-order policy (root above media).
     *
     * @throws Violations {@code instance_not_permitted}, {@code devices_unsupported},
     *         {@code device_exists}, {@code device_attach_failed}, plus the model's
     *         name/media invariants
     */
    public void attachCdrom(int instanceId, @NonNull String name, @NonNull String mediaVolume) {
        HohenheimAccess.requireOperatorOperation();
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());
        requireAbsentRow(instanceId, name);

        Row row = Models.get(InstanceDeviceModel.class).createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        row.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_CDROM);
        row.set(InstanceDeviceModel.NAME, name);
        row.set(InstanceDeviceModel.SOURCE_MEDIA, mediaVolume);
        Models.get(InstanceDeviceModel.class).save(row);

        if (workloadAbsent(resolved)) {
            return;   // desired state recorded; deploy's reconcile attaches it
        }
        try {
            support.ensureCdrom(resolved.spec(), name, mediaVolume);
        } catch (IOException e) {
            Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
            throw refusal("device_attach_failed", resolved.row(), name, e);
        }
    }

    /**
     * Detach one device: daemon first (device removed, disk volume deleted VERIFIED),
     * row second -- an unreachable daemon keeps the row AND its reservation, because
     * the disk still exists.
     *
     * @throws Violations {@code device_not_found}, {@code device_detach_failed}
     */
    public void detach(int instanceId, @NonNull String name) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());

        Row row = rowOf(instanceId, name);
        if (row == null) {
            throw Violations.ofField("name", name, violationText("device_not_found")
                .withArg("device", name));
        }
        boolean disk = InstanceDeviceModel.TYPE_DISK.equals(row.get(InstanceDeviceModel.TYPE));
        try {
            support.removeDevice(resolved.spec(), name, disk);
        } catch (IOException e) {
            throw refusal("device_detach_failed", resolved.row(), name, e);
        }
        // Hard delete: the remove-hook pairing releases the reservation.
        Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
    }

    /** The device rows of one instance (admin surfaces, reconcile, tests). */
    public @NonNull List<Row> rowsFor(int instanceId) {
        return Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .all();
    }

    /**
     * Deploy-time reconcile: ensure every desired device row exists at the daemon
     * (idempotent per the capability contract), so a wipe-and-recreate deploy comes
     * back with its disks and NICs. Called between create and start.
     *
     * @throws IOException when the daemon refuses; the deploy's own failure lane
     *         handles it
     * @throws Violations {@code devices_unsupported} when rows exist on a driver
     *         without the capability
     */
    void reconcile(@NonNull Resolved resolved, int instanceId) throws IOException {
        List<Row> rows = rowsFor(instanceId);
        if (rows.isEmpty()) {
            return;
        }
        DeviceAttachSupport support = requireSupport(resolved);
        for (Row row : rows) {
            String name = row.get(InstanceDeviceModel.NAME);
            String type = row.get(InstanceDeviceModel.TYPE);
            if (InstanceDeviceModel.TYPE_DISK.equals(type)) {
                Integer size = row.get(InstanceDeviceModel.SIZE_GB);
                support.ensureDisk(resolved.spec(), name, size == null ? 1 : size);
            } else if (InstanceDeviceModel.TYPE_CDROM.equals(type)) {
                String media = row.get(InstanceDeviceModel.SOURCE_MEDIA);
                support.ensureCdrom(resolved.spec(), name, media == null ? "" : media);
            } else {
                support.ensureNic(resolved.spec(), name);
            }
        }
    }

    /**
     * Destroy-time cleanup: delete the backing volumes at the daemon (VERIFIED; the
     * workload itself is already gone, its devices with it) and hard-delete the rows,
     * releasing every reservation. Explicit because destroy soft-deletes the instance
     * and remove hooks never fire there (the GameDomains.deleteForInstance shape).
     *
     * @throws IOException when a volume could not be confirmed gone -- the destroy
     *         refuses and the operator retries
     */
    void destroyCleanup(@NonNull Resolved resolved, int instanceId) throws IOException {
        List<Row> rows = rowsFor(instanceId);
        if (rows.isEmpty()) {
            return;
        }
        if (resolved.runtime() instanceof DeviceAttachSupport support) {
            List<String> diskNames = new ArrayList<>();
            for (Row row : rows) {
                if (InstanceDeviceModel.TYPE_DISK.equals(row.get(InstanceDeviceModel.TYPE))) {
                    diskNames.add(row.get(InstanceDeviceModel.NAME));
                }
            }
            support.deleteVolumes(resolved.spec(), diskNames);
        }
        for (Row row : rows) {
            Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
        }
        Blast.log("INSTANCE: removed", rows.size(), "device row(s) of instance", instanceId,
            "- volumes deleted at the daemon, reservations released");
    }

    // -- plumbing -------------------------------------------------------------

    private static @Nullable Row rowOf(int instanceId, @NonNull String name) {
        return Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceDeviceModel.NAME.eq(name))
            .first();
    }

    private static void requireAbsentRow(int instanceId, @NonNull String name) {
        if (rowOf(instanceId, name) != null) {
            throw Violations.ofField("name", name, violationText("device_exists")
                .withArg("device", name));
        }
    }

    private static @NonNull DeviceAttachSupport requireSupport(@NonNull Resolved resolved) {
        if (resolved.runtime() instanceof DeviceAttachSupport support) {
            return support;
        }
        throw Violations.ofForm(violationText("devices_unsupported")
            .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
    }

    private static boolean workloadAbsent(@NonNull Resolved resolved) {
        return resolved.runtime().status(resolved.spec().handle()).state()
            == ContainerState.ABSENT;
    }

    private static void revertSize(@NonNull Row row, @Nullable Integer before) {
        row.set(InstanceDeviceModel.SIZE_GB, before);
        Models.get(InstanceDeviceModel.class).save(row);
    }

    private static Violations refusal(String key, Row instanceRow, String device,
                                      IOException cause) {
        return Violations.ofForm(violationText(key)
            .withArg("name", String.valueOf((Object) instanceRow.get(InstanceModel.NAME)))
            .withArg("device", device)
            .withArg("reason", cause.getMessage() != null ? cause.getMessage()
                : cause.toString()));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
