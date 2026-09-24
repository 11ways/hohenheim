package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static be.elevenways.hohenheim.server.runtime.IncusDefinitions.USER_PREFIX;

/**
 * The snapshot, backup and image lane of {@link IncusInstanceRuntime}: the daemon's own
 * pool-resident snapshots, whole-instance export and import, and publishing a stopped
 * workload as an image.
 *
 * AIDEV-NOTE: split out of IncusInstanceRuntime mechanically; the runtime still implements
 * {@link NativeSnapshotSupport} and {@link ImagePublishSupport} and delegates here.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class IncusArchiveLane {

    private final @NonNull IncusInstanceRuntime runtime;
    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;

    IncusArchiveLane(@NonNull IncusInstanceRuntime runtime, @NonNull IncusClient incus,
                     @NonNull IncusNetworkPolicy policy, @NonNull Egress egress) {
        this.runtime = runtime;
        this.incus = incus;
        this.policy = policy;
        this.egress = egress;
    }

    void createSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.createSnapshot(spec.handle(), snapshotName);
    }

    boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.snapshot(spec.handle(), snapshotName);
            return true;
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        }
    }

    void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.restoreSnapshot(spec.handle(), snapshotName);
    }

    void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.deleteSnapshot(spec.handle(), snapshotName);
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // refused: NOT gone
            }
            // 404 = observed absent, which is what delete exists to establish.
        }
    }

    long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination, long maxBytes,
                      boolean withSnapshots) throws IOException {
        // The daemon-side backup object is a TEMPORARY: the export tarball is the
        // artifact, and leaving the object behind would silently fill the pool.
        String backupName = "hib-" + Now.millis();
        this.incus.createBackup(spec.handle(), backupName, !withSnapshots);
        try {
            return this.incus.exportBackup(spec.handle(), backupName, destination, maxBytes);
        } finally {
            try {
                this.incus.deleteBackup(spec.handle(), backupName);
            } catch (IOException cleanupFailed) {
                Blast.log("INCUS: could not remove temporary backup object", backupName,
                    "of", spec.handle(), ":", cleanupFailed.getMessage());
            }
        }
    }

    void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive) throws IOException {
        // AIDEV-NOTE: the isolation ACL is ensured BEFORE the import, on THIS daemon. The
        // archive carries the source's device config, and its eth0 names this
        // controller's isolation ACL (`security.acls`); the daemon validates every
        // device while creating the instance record, so a destination that has never
        // placed one of our workloads refused the whole import with `Network ACL
        // "hohenheim-<token>-isolation" does not exist` (F1, cold migration daystrom ->
        // nightstrom, 2026-08-29). The live proof never caught it because its peer
        // container deployed on the destination first, which created the ACL there
        // through the ordinary create() path. This is the SAME ensure the source used
        // (conditional write, verified read-back), never a copy of the source's object
        // -- and doing it here is strictly safer than after the import: a conditional
        // UPDATE of a stale ACL retriggers every referencing NIC, and before the import
        // the clone that could carry a duplicate MAC does not exist yet.
        this.policy.ensureIsolationAcl();
        this.runtime.stampPresence();
        this.incus.importInstance(archive, spec.handle());
        // Re-identification is part of the import contract: the tarball carries the
        // SOURCE instance's user.* labels (until they are replaced the import is
        // attributed to the wrong record -- a crash inside the window leaves an
        // instance the NEW record's next deploy refuses as foreign, visible operator
        // cleanup, never silent adoption) AND the source's volatile NIC MACs, which
        // the daemon refuses beside the still-running source ("MAC address already
        // defined on another NIC"). Dropping the hwaddr keys makes the daemon mint
        // fresh ones at start.
        Map<String, Object> existing = this.incus.instance(spec.handle());
        boolean carriedMacs = existing.get("config") instanceof Map<?, ?> current
            && current.keySet().stream().map(String::valueOf).anyMatch(IncusDefinitions::isVolatileMac);
        IncusClient.DefinitionEdit reidentify = (config, devices) -> {
            config.keySet().removeIf(IncusDefinitions::isVolatileMac);
            spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        };
        // AIDEV-NOTE: the MAC strip is its OWN write, BEFORE the post-import
        // ensureIsolationAcl, and the order is load-bearing. Between import and the
        // strip the clone and its source share a MAC at the daemon, and ANY ACL write in
        // that window makes the daemon re-trigger every referencing NIC and fail 409 on
        // the duplicate (observed live on the source instance, not the clone). This
        // write touches only the clone's own definition -- devices unchanged -- so it
        // cannot trip over other instances.
        if (carriedMacs) {
            this.incus.editInstance(spec.handle(), reidentify);
        }
        // An imported instance re-joins the fleet's isolation exactly like a fresh one:
        // its NIC gets the verified ACL override, so a backup made before isolation
        // existed cannot land an unisolated container. The pre-import ensure above
        // already converged the ACL, so this call writes nothing and is the read-back
        // VERIFICATION that the daemon still carries every tenant-range reject.
        this.policy.ensureIsolationAcl();
        Map<String, Object> nic = this.policy.nicDevice(
            IncusDefinitions.managedNetworkName(this.incus), this.egress, spec.networkLimitMbit());
        this.incus.editInstance(spec.handle(), (config, devices) -> {
            reidentify.apply(config, devices);
            devices.put(IncusNetworkPolicy.NIC, nic);
        });
        this.runtime.verifyIsolated(spec);
    }

    /**
     * @throws IOException for a RUNNING or absent workload: publishing a live rootfs
     *         would capture a torn filesystem, and Incus's own refusal for that case is
     *         less specific than the state this driver can already read
     */
    @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                 @Nullable String description) throws IOException {
        ContainerState state = this.runtime.status(spec.handle()).state();
        if (state != ContainerState.STOPPED) {
            throw new IOException("REFUSED to publish '" + spec.handle() + "' as an image:"
                + " the workload is " + state + " and only a STOPPED workload captures a"
                + " consistent filesystem. Stop it first.");
        }
        return this.incus.publishImage(spec.handle(), alias, description);
    }
}
