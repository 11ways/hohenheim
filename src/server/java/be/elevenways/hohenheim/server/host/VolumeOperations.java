package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.platform.PlatformSeam;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;

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
     * Remove an owner's folder ({@code <root>/<instance>}) and its snapshot mirror when nothing is left in them; a
     * folder that still holds anything, or is absent, stays as it is.
     */
    void pruneOwner(@NonNull String ownerPath);

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
     * Capture the volume's contents as a tar at {@code target}, numeric owners kept, from a point-in-time copy so a
     * running workload's writes cannot tear it.
     *
     * @param maxBytes cap on the tar, enforced while it is written
     * @return the tar's size in bytes
     */
    long archive(@NonNull String hostPath, @NonNull Path target, long maxBytes);

    /**
     * Unpack a tar {@link #archive} wrote into the (fresh) volume.
     *
     * @param ownerUid the uid every unpacked entry is given to, or null to keep the captured owners: a workspace's
     *                 files belong to its derived uid, which a restored record does not share with its source
     */
    void restore(@NonNull String hostPath, @NonNull Path source, @Nullable Integer ownerUid);

    /**
     * Measures the filesystem holding a path.
     *
     * AIDEV-NOTE: every member answers, the unimplemented ones too: measuring reads the filesystem and creates no
     * volume, so it is no capability a backend could lack ({@link #spaceOver}).
     *
     * @return the size and free bytes, null when the host could not answer
     */
    @Nullable DiskSpace space(@NonNull String path);

    /**
     * Measures the filesystem holding a path's deepest existing ancestor, so a volume root that is created on first use
     * still reads.
     *
     * @return the size and free bytes, null when the host could not answer
     */
    static @Nullable DiskSpace spaceOver(@NonNull HostShell shell, @NonNull String path) {
        HostShell.Result result = shell.run("p=" + HostShell.quote(path) + "; "
            + "while [ ! -e \"$p\" ] && [ \"$p\" != \"/\" ]; do p=$(dirname \"$p\"); done; "
            + "df -B1 --output=size,avail \"$p\"");
        String[] lines = result.text().trim().split("\n");
        try {
            String[] figures = lines[lines.length - 1].trim().split("\\s+");
            if (result.ok() && figures.length == 2) {
                return new DiskSpace(Long.parseLong(figures[0]), Long.parseLong(figures[1]));
            }
        } catch (IllegalArgumentException unparseable) {
            // unanswerable, like a failed df
        }
        Blast.log("VOLUMES: could not measure the space of", path, "-", result.text());
        return null;
    }

    /** @return bytes free on the filesystem holding {@code path}, or -1 when the host could not answer */
    default long availableBytes(@NonNull String path) {
        DiskSpace space = this.space(path);
        return space == null ? -1 : space.freeBytes();
    }

    /** Who answers for one host's volume operations; a test installs a directory-backed one. */
    @FunctionalInterface
    interface Hosts {
        @NonNull VolumeOperations forServer(@NonNull Row server);

        /** Which storage a host keeps its volumes on: two hosts answering alike name one directory tree. */
        default @NonNull String storageOf(@NonNull Row server) {
            if (!ServerModel.hasSshLane(server)) {
                return "local";
            }
            String target = String.valueOf((Object) server.get(ServerModel.SSH_TARGET));
            return "ssh:" + target.substring(target.indexOf('@') + 1);
        }
    }

    /** The seam behind {@link #forServer}: the host's detected backend over its shell. */
    PlatformSeam<Hosts> HOSTS = PlatformSeam.withDefault(Hosts.class,
        server -> forBackend(ServerModel.volumeBackendOf(server), HostShell.forServer(server)));

    /** The operations of one inventoried host. */
    static @NonNull VolumeOperations forServer(@NonNull Row server) {
        return HOSTS.require().forServer(server);
    }

    /**
     * The operations of one host's backend.
     *
     * @throws Violations for a backend nothing implements yet, naming it
     */
    static @NonNull VolumeOperations forBackend(@NonNull VolumeBackend backend,
                                                @NonNull HostShell shell) {
        return switch (backend) {
            case BTRFS -> new BtrfsVolumeOperations(shell);
            case ZFS, XFS_PRJQUOTA, NONE, UNCHECKED -> new UnimplementedVolumeOperations(backend, shell);
        };
    }

    /** The refusal every unimplemented backend answers every volume operation with; measuring still answers. */
    final class UnimplementedVolumeOperations implements VolumeOperations {

        private final VolumeBackend backend;
        private final HostShell shell;

        UnimplementedVolumeOperations(@NonNull VolumeBackend backend, @NonNull HostShell shell) {
            this.backend = backend;
            this.shell = shell;
        }

        private Violations refuse() {
            return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("volume_backend_unimplemented")
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

        @Override public void pruneOwner(@NonNull String ownerPath) { throw refuse(); }

        @Override public void own(@NonNull String hostPath, int uid) { throw refuse(); }

        @Override public long archive(@NonNull String hostPath, @NonNull Path target, long maxBytes) {
            throw refuse();
        }

        @Override public void restore(@NonNull String hostPath, @NonNull Path source, @Nullable Integer ownerUid) {
            throw refuse();
        }

        @Override public @Nullable DiskSpace space(@NonNull String path) { return spaceOver(this.shell, path); }
    }
}
