package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DeviceAttachSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
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

    /**
     * THE capability every device operation demands on the device's instance; the write gate ({@link #target}) and
     * the render faces below ask this one name.
     */
    private static final String DEVICE_CAPABILITY = HohenheimAccess.CONFIG;

    private final @NonNull InstanceService instances;

    public InstanceDevices() {
        this(new InstanceService());
    }

    public InstanceDevices(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * The render face of the device write gate for one instance: whether an attach, resize or detach on it would pass
     * the capability check every mutator makes first.
     *
     * AIDEV-NOTE: rides the request memo ({@code reachesRecord}) because a list asks it per rendered row; the write
     * gate keeps the fresh walk. Same capability, so the offer and the refusal cannot drift.
     */
    public static boolean mayChangeDevicesOf(@NonNull AccessContext access, @Nullable Integer instanceId) {
        return HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, instanceId, DEVICE_CAPABILITY);
    }

    /**
     * The record-less render face of the device write gate: whether the principal could attach a device to ANY
     * instance, which is what a create offered without a target instance asks.
     */
    public static boolean mayChangeAnyDevices(@NonNull AccessContext access) {
        return HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID, DEVICE_CAPABILITY);
    }

    /**
     * Attach a data disk of {@code sizeGb} under quota.
     *
     * @throws Violations {@code devices_unsupported}, {@code device_exists},
     *         {@code disk_quota_reached}, {@code device_attach_failed}, plus the
     *         model's name/size invariants
     */
    public void attachDisk(int instanceId, @NonNull String name, int sizeGb) {
        Target target = this.target(instanceId);
        Resolved resolved = target.resolved();
        DeviceAttachSupport support = target.support();
        requireAbsentRow(instanceId, name);

        Row row = newDevice(instanceId, DeviceType.DISK, name);
        row.set(InstanceDeviceModel.SIZE_GB, sizeGb);
        this.attach(target, row, name, () -> support.ensureDisk(resolved.spec(), name, sizeGb));
    }

    /**
     * Resize a data disk under quota (the DELTA is what the ledger judges).
     *
     * @throws Violations {@code device_not_found}, {@code disk_quota_reached},
     *         {@code device_resize_failed} -- the latter carrying the daemon's own
     *         refusal verbatim (notably "In use": block volumes resize stopped only)
     */
    public void resizeDisk(int instanceId, @NonNull String name, int sizeGb) {
        Target target = this.target(instanceId);
        Resolved resolved = target.resolved();
        DeviceAttachSupport support = target.support();

        Row row = rowOf(instanceId, name);
        if (row == null || DeviceType.parse(row.get(InstanceDeviceModel.TYPE)) != DeviceType.DISK) {
            throw Violations.ofField("name", name, HohenheimViolations.text("device_not_found")
                .withArg("device", name));
        }
        Integer before = row.get(InstanceDeviceModel.SIZE_GB);
        row.set(InstanceDeviceModel.SIZE_GB, sizeGb);
        Models.get(InstanceDeviceModel.class).save(row);   // delta reserved/released here

        try {
            if (support.diskSizeGb(resolved.spec(), name) == null) {
                return;   // volume not materialized yet; deploy's reconcile creates it at size
            }
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
        Target target = this.target(instanceId);
        Resolved resolved = target.resolved();
        DeviceAttachSupport support = target.support();
        requireAbsentRow(instanceId, name);

        Row row = newDevice(instanceId, DeviceType.NIC, name);
        this.attach(target, row, name, () -> support.ensureNic(resolved.spec(), name));
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
        Target target = this.target(instanceId);
        Resolved resolved = target.resolved();
        DeviceAttachSupport support = target.support();
        requireAbsentRow(instanceId, name);

        Row row = newDevice(instanceId, DeviceType.CDROM, name);
        row.set(InstanceDeviceModel.SOURCE_MEDIA, mediaVolume);
        this.attach(target, row, name, () -> support.ensureCdrom(resolved.spec(), name, mediaVolume));
    }

    /**
     * Detach one device: daemon first (device removed, disk volume deleted VERIFIED),
     * row second -- an unreachable daemon keeps the row AND its reservation, because
     * the disk still exists.
     *
     * @throws Violations {@code device_not_found}, {@code device_detach_failed}
     */
    public void detach(int instanceId, @NonNull String name) {
        Target target = this.target(instanceId);
        Resolved resolved = target.resolved();
        DeviceAttachSupport support = target.support();

        Row row = rowOf(instanceId, name);
        if (row == null) {
            throw Violations.ofField("name", name, HohenheimViolations.text("device_not_found")
                .withArg("device", name));
        }
        // Symmetry with attachCdrom: install media is an OPERATOR device end to
        // end, so a CONFIG-holding tenant must not be able to eject it either
        // (mid-install, say). The refusal is the tier's uniform one -- checked
        // AFTER the row load so the funnel stays the authority, not the form.
        // A row whose type is no member stays DETACHABLE (it is what an operator removes
        // when reconcile refuses it), but only by an operator, and no volume is deleted
        // for it: there is no member to say it owns one.
        DeviceType type = DeviceType.parse(row.get(InstanceDeviceModel.TYPE));
        if (type == null || type.operatorOnly()) {
            HohenheimAccess.requireOperatorOperation();
        }
        try {
            support.removeDevice(resolved.spec(), name, type != null && type.ownsVolume());
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
     *         without the capability; {@code device_type_unknown} for a row whose type
     *         is no {@link DeviceType} member
     */
    void reconcile(@NonNull Resolved resolved, int instanceId) throws IOException {
        List<Row> rows = rowsFor(instanceId);
        if (rows.isEmpty()) {
            return;
        }
        DeviceAttachSupport support = requireSupport(resolved);
        for (Row row : rows) {
            String name = row.get(InstanceDeviceModel.NAME);
            // Exhaustive over the vocabulary, and a token that is no member REFUSES the
            // deploy by name: the old else-branch ensured a NIC for anything it did not
            // recognize, so a mistyped row came back as a network interface.
            DaemonStep ensure = switch (DeviceType.require(row.get(InstanceDeviceModel.TYPE))) {
                case DISK -> () -> {
                    Integer size = row.get(InstanceDeviceModel.SIZE_GB);
                    support.ensureDisk(resolved.spec(), name, size == null ? 1 : size);
                };
                case NIC -> () -> support.ensureNic(resolved.spec(), name);
                case CDROM -> () -> {
                    String media = row.get(InstanceDeviceModel.SOURCE_MEDIA);
                    support.ensureCdrom(resolved.spec(), name, media == null ? "" : media);
                };
            };
            ensure.run();
        }
    }

    /** One daemon call a device operation makes. */
    @FunctionalInterface
    private interface DaemonStep {
        void run() throws IOException;
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
                // An unknown token owns no volume we can name; its row still goes below.
                DeviceType type = DeviceType.parse(row.get(InstanceDeviceModel.TYPE));
                if (type != null && type.ownsVolume()) {
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
            throw Violations.ofField("name", name, HohenheimViolations.text("device_exists")
                .withArg("device", name));
        }
    }

    private static @NonNull DeviceAttachSupport requireSupport(@NonNull Resolved resolved) {
        if (resolved.runtime() instanceof DeviceAttachSupport support) {
            return support;
        }
        throw Violations.ofForm(HohenheimViolations.text("devices_unsupported")
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

    /** The resolved instance and its device support, once every check a device operation makes first passed. */
    private record Target(@NonNull Resolved resolved, @NonNull DeviceAttachSupport support) {}

    /**
     * The checks every device operation makes first: config on the instance, an operable record, a runtime that
     * attaches devices, and this controller's fence on its host.
     */
    private @NonNull Target target(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, DEVICE_CAPABILITY);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        DeviceAttachSupport support = requireSupport(resolved);
        this.instances.leases().requireFence(resolved.serverId());
        return new Target(resolved, support);
    }

    private static @NonNull Row newDevice(int instanceId, @NonNull DeviceType type, @NonNull String name) {
        Row row = Models.get(InstanceDeviceModel.class).createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        row.set(InstanceDeviceModel.TYPE, type.token());
        row.set(InstanceDeviceModel.NAME, name);
        return row;
    }

    /**
     * Records a new device, then puts it on a running workload.
     *
     * AIDEV-NOTE: the quota reservation fires on the row write (beforeWrite, adjacent to the write), so a full bucket
     * refuses before any daemon contact; a workload that is not there keeps the desired state for deploy's reconcile;
     * a daemon that does not carry the device takes the row, and with it the reservation, back out of the ledger.
     */
    private void attach(@NonNull Target target, @NonNull Row row, @NonNull String name,
                        @NonNull DaemonStep daemon) {
        Models.get(InstanceDeviceModel.class).save(row);
        if (workloadAbsent(target.resolved())) {
            return;
        }
        try {
            daemon.run();
        } catch (IOException e) {
            Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID));
            throw refusal("device_attach_failed", target.resolved().row(), name, e);
        }
    }

    private static Violations refusal(String key, Row instanceRow, String device,
                                      IOException cause) {
        return Violations.ofForm(HohenheimViolations.instanceRefusalText(key, instanceRow, cause)
            .withArg("device", device));
    }

}
