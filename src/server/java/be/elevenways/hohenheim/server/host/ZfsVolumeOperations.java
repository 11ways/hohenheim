package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.util.PosixPaths;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The ZFS member's operations: one dataset per volume below the dataset mounted at the volume root, a
 * {@code refquota} per dataset, read-only snapshots reached through the dataset's own {@code .zfs/snapshot} folder.
 *
 * AIDEV-NOTE: the volume root must be a dataset's OWN mountpoint, so a child dataset {@code <root>/<instance>/<name>}
 * mounts exactly at the volume's host path; a volume root that is a plain folder inside some dataset is refused,
 * since a dataset created below it would mount elsewhere and the workload would write past its quota.
 *
 * AIDEV-NOTE: {@code refquota}, not {@code quota}: it caps what the volume itself references, its snapshots
 * excluded, which is what the btrfs member's qgroup limit caps too. A pre-deploy snapshot therefore never eats the
 * volume's own room.
 *
 * AIDEV-NOTE: every command is idempotent by construction, as the btrfs member's are: a create over an existing
 * dataset, a destroy of an absent one and a quota re-apply all succeed.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class ZfsVolumeOperations extends ShellVolumeOperations {

    /**
     * What ZFS accepts in a dataset or snapshot name component, never {@code .}, {@code ..} or a leading dash;
     * anything else is refused before the host sees it.
     */
    private static final Pattern NAME_COMPONENT = Pattern.compile("(?!\\.\\.?$)(?!-)[A-Za-z0-9_.:-]+");

    /** The folder ZFS shows a dataset's snapshots in, below its mountpoint. */
    static final String SNAPSHOT_FOLDER = ".zfs/snapshot";

    public ZfsVolumeOperations(@NonNull HostShell shell) {
        super(shell);
    }

    @Override
    protected @NonNull VolumeBackend backend() {
        return VolumeBackend.ZFS;
    }

    @Override
    public void create(@NonNull String hostPath) {
        run(privileged(PrivilegedHelper.Verb.VOLUME_CREATE, dataset(hostPath)
                + " && { " + this.sudo + "zfs list -H -o name \"$ds\" >/dev/null 2>&1"
                + " || " + this.sudo + "zfs create -p \"$ds\"; }", hostPath),
            "volume_create_failed", hostPath);
    }

    @Override
    public void setQuota(@NonNull String hostPath, @Nullable Long bytes) {
        String limit = bytes == null || bytes <= 0 ? "none" : String.valueOf(bytes);
        run(privileged(PrivilegedHelper.Verb.VOLUME_QUOTA, dataset(hostPath)
                + " && " + this.sudo + "zfs set refquota=" + limit + " \"$ds\"", hostPath, limit),
            "volume_quota_failed", hostPath);
    }

    @Override
    public long usage(@NonNull String hostPath) {
        HostShell.Result result = this.shell.run(privileged(PrivilegedHelper.Verb.VOLUME_USAGE, dataset(hostPath)
            + " && " + this.sudo + "zfs get -Hp -o value referenced \"$ds\"", hostPath));
        if (!result.ok()) {
            Blast.log("VOLUMES: zfs could not report usage of", hostPath, "-", result.text());
            return -1;
        }
        return referencedBytes(result.text());
    }

    /** @return the byte count {@code zfs get -Hp -o value referenced} printed, -1 when it printed none */
    static long referencedBytes(@NonNull String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("\\d+")) {
                return Long.parseLong(trimmed);
            }
        }
        return -1;
    }

    @Override
    public @NonNull String snapshot(@NonNull String hostPath, @NonNull String label) {
        String name = snapshotNameFor(label);
        String target = hostPath + "/" + SNAPSHOT_FOLDER + "/" + name;
        run(privileged(PrivilegedHelper.Verb.VOLUME_SNAPSHOT, dataset(hostPath)
                + " && " + this.sudo + "zfs snapshot \"$ds\"@" + HostShell.quote(name), hostPath, target),
            "volume_snapshot_failed", hostPath);
        return target;
    }

    @Override
    public void deleteSnapshot(@NonNull String snapshotPath) {
        Snapshot snapshot = Snapshot.of(snapshotPath);
        if (snapshot == null) {
            throw failure("volume_destroy_failed", snapshotPath, "not a ZFS snapshot path");
        }
        run(privileged(PrivilegedHelper.Verb.VOLUME_DESTROY, dataset(snapshot.volume())
                + " && if " + this.sudo + "zfs list -H -t snapshot -o name \"$ds\"@" + HostShell.quote(snapshot.name())
                + " >/dev/null 2>&1; then " + this.sudo + "zfs destroy \"$ds\"@" + HostShell.quote(snapshot.name())
                + "; fi", snapshotPath),
            "volume_destroy_failed", snapshotPath);
    }

    @Override
    public void destroy(@NonNull String hostPath) {
        run(privileged(PrivilegedHelper.Verb.VOLUME_DESTROY, dataset(hostPath)
                + " && if " + this.sudo + "zfs list -H -o name \"$ds\" >/dev/null 2>&1; then "
                + this.sudo + "zfs destroy -r \"$ds\";"
                // A path something is still mounted at is a dataset zfs list failed to answer for, never a folder.
                + " elif mountpoint -q " + HostShell.quote(hostPath) + "; then echo "
                + HostShell.quote(hostPath + " is still mounted") + " >&2; false;"
                + " else " + this.sudo + "rm -rf " + HostShell.quote(hostPath) + "; fi", hostPath),
            "volume_destroy_failed", hostPath);
    }

    /** The owner's dataset goes once no volume dataset is left below it; a plain folder once it holds nothing. */
    @Override
    public void pruneOwner(@NonNull String ownerPath) {
        String quoted = HostShell.quote(ownerPath);
        run(privileged(PrivilegedHelper.Verb.VOLUME_PRUNE, rootDataset(PosixPaths.parentOf(ownerPath))
                + " && ds=\"$root\"/" + HostShell.quote(component(PosixPaths.nameOf(ownerPath), ownerPath))
                + " && if " + this.sudo + "zfs list -H -o name \"$ds\" >/dev/null 2>&1; then"
                + " if [ \"$(" + this.sudo + "zfs list -H -r -d 1 -o name \"$ds\" | wc -l)\" -eq 1 ]; then "
                + this.sudo + "zfs destroy \"$ds\"; fi;"
                + " elif [ -d " + quoted + " ] && [ ! -L " + quoted + " ]; then " + this.sudo
                + "rmdir --ignore-fail-on-non-empty " + quoted + "; fi", ownerPath),
            "volume_destroy_failed", ownerPath);
    }

    /** A snapshot path split back into its volume and its snapshot name. */
    record Snapshot(@NonNull String volume, @NonNull String name) {

        /** @return the volume and snapshot name of a {@code <volume>/.zfs/snapshot/<name>} path, null for any other */
        static @Nullable Snapshot of(@NonNull String snapshotPath) {
            int folder = snapshotPath.lastIndexOf("/" + SNAPSHOT_FOLDER + "/");
            String name = folder < 0 ? "" : snapshotPath.substring(folder + SNAPSHOT_FOLDER.length() + 2);
            if (folder <= 0 || !NAME_COMPONENT.matcher(name).matches()) {
                return null;
            }
            return new Snapshot(snapshotPath.substring(0, folder), name);
        }
    }

    /** @return the snapshot name for a label: the label made plain, then the instant it was taken */
    private static @NonNull String snapshotNameFor(@NonNull String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        String stamp = Now.instant().toString().replace(':', '-').replace('.', '-');
        String plain = cleaned.replaceAll("^[-.]+", "");
        return (plain.isBlank() ? "snapshot" : plain) + "-" + stamp;
    }

    /**
     * The script lines that set {@code $ds} to a volume's dataset: the volume root's own dataset, then the volume's
     * instance and name below it.
     */
    private @NonNull String dataset(@NonNull String hostPath) {
        String owner = PosixPaths.parentOf(hostPath);
        return rootDataset(PosixPaths.parentOf(owner))
            + " && ds=\"$root\"/" + HostShell.quote(component(PosixPaths.nameOf(owner), hostPath)) + "/"
            + HostShell.quote(component(PosixPaths.nameOf(hostPath), hostPath));
    }

    /** The script lines that set {@code $root} to the dataset mounted exactly at the volume root, or fail. */
    private @NonNull String rootDataset(@NonNull String volumeRoot) {
        String quoted = HostShell.quote(volumeRoot);
        return "root=$(" + this.sudo + "zfs list -H -o name " + quoted + ")"
            + " && [ \"$(" + this.sudo + "zfs get -H -o value mountpoint \"$root\")\" = " + quoted + " ]"
            + " || { echo " + HostShell.quote(volumeRoot + " is not the mountpoint of a ZFS dataset") + " >&2; false; }";
    }

    /** @throws Violations when a path component is no name ZFS accepts */
    private @NonNull String component(@NonNull String name, @NonNull String hostPath) {
        if (!NAME_COMPONENT.matcher(name).matches()) {
            throw failure("volume_create_failed", hostPath, "'" + name + "' is no ZFS dataset name");
        }
        return name;
    }
}
