package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What a host's volume backend can DO to one volume directory -- the per-member operations
 * {@link VolumeBackend} declares the capabilities of.
 *
 * AIDEV-NOTE: the capability FACTS live on the enum (common, TeaVM-safe); the operations
 * live here because every one of them is a command on a host, which common source may not
 * contain. {@link #forBackend} is an EXHAUSTIVE switch with no default arm, so adding a
 * backend member breaks this build until someone decides what it can do -- an unknown
 * backend can never silently fall through to "works like btrfs".
 *
 * AIDEV-NOTE: only btrfs is implemented. ZFS and XFS project quota refuse BY NAME rather
 * than degrading, because a volume whose quota was never applied looks exactly like one
 * whose quota is enforced until the day it fills the host's disk.
 *
 * AIDEV-NOTE: which members this file implements is DECLARED on the enum
 * ({@link VolumeBackend#isImplemented}), and that declaration is what the picker and
 * placement narrow on. The two cannot be trusted to agree by inspection -- they did not,
 * from the day ZFS was declared quota-capable until 2026-08-23 -- so
 * {@code VolumeOperationsTest.theImplementedFlagIsWhatTheOperationsActuallyDo} drives every
 * member through this factory and fails the build when a flag and a behaviour disagree.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface VolumeOperations {

    /** Create the volume directory, idempotently. */
    void create(@NonNull String hostPath);

    /**
     * Enforce a size cap on a volume.
     *
     * @param bytes the cap, or null/0 to clear it
     */
    void setQuota(@NonNull String hostPath, @Nullable Long bytes);

    /** @return bytes referenced by the volume, or -1 when the backend could not answer */
    long usage(@NonNull String hostPath);

    /**
     * Take a point-in-time read-only copy beside the volume.
     *
     * @return the snapshot's host path
     */
    @NonNull String snapshot(@NonNull String hostPath, @NonNull String label);

    void deleteSnapshot(@NonNull String snapshotPath);

    /** Remove the volume and everything in it. */
    void destroy(@NonNull String hostPath);

    /**
     * Give the volume directory itself to a uid, so the workload can write in its own home.
     *
     * AIDEV-NOTE: the DIRECTORY only, never a recursive chown. A workspace's home holds a
     * checkout and a node_modules tree; walking it on every deploy is minutes of host I/O,
     * and a recursive chown would also rewrite ownership a build deliberately set. The
     * files inside are already the workload's own, because the workload is the only thing
     * that ever writes them.
     */
    void own(@NonNull String hostPath, int uid);

    /**
     * The operations of one host's backend.
     *
     * @throws Violations for a backend nothing implements yet, naming it
     */
    static @NonNull VolumeOperations forBackend(@NonNull VolumeBackend backend,
                                                @NonNull HostShell shell) {
        return switch (backend) {
            case BTRFS -> new BtrfsVolumeOperations(shell);
            case ZFS, XFS_PRJQUOTA, NONE -> new UnimplementedVolumeOperations(backend);
        };
    }

    /** The refusal every unimplemented backend answers every operation with. */
    final class UnimplementedVolumeOperations implements VolumeOperations {

        private final VolumeBackend backend;

        UnimplementedVolumeOperations(@NonNull VolumeBackend backend) {
            this.backend = backend;
        }

        private Violations refuse() {
            return Violations.ofForm(Microcopy.of("volume_backend_unimplemented")
                .withFilter("scope", "violations")
                .withArg("backend", this.backend.label()));
        }

        @Override public void create(@NonNull String hostPath) { throw refuse(); }

        @Override public void setQuota(@NonNull String hostPath, @Nullable Long bytes) {
            throw refuse();
        }

        @Override public long usage(@NonNull String hostPath) { throw refuse(); }

        @Override public @NonNull String snapshot(@NonNull String hostPath,
                                                  @NonNull String label) {
            throw refuse();
        }

        @Override public void deleteSnapshot(@NonNull String snapshotPath) { throw refuse(); }

        @Override public void destroy(@NonNull String hostPath) { throw refuse(); }

        @Override public void own(@NonNull String hostPath, int uid) { throw refuse(); }
    }
}
