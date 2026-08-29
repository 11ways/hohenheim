package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Locale;

/**
 * The btrfs member's operations: one subvolume per volume, a qgroup limit per subvolume,
 * read-only snapshots beside it.
 *
 * AIDEV-NOTE: every command is idempotent BY CONSTRUCTION rather than by probing first --
 * a create over an existing subvolume, a destroy of an absent one and a quota re-apply all
 * succeed. A probe-then-act pair over a host shell is two round trips with a race between
 * them, and the loser of that race is a deploy that refuses over a directory that exists.
 *
 * AIDEV-NOTE: {@code btrfs quota enable} is run before every limit. It is a no-op on a
 * filesystem that already has quotas on, and without it {@code qgroup limit} fails on a
 * freshly carved filesystem -- the first workspace on a new host is exactly the case
 * nobody would have tested by hand.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class BtrfsVolumeOperations implements VolumeOperations {

    /** Where a snapshot of {@code <root>/<id>/<name>} lands: {@code <root>/.snapshots/...}. */
    public static final String SNAPSHOT_DIRECTORY = ".snapshots";

    private final HostShell shell;

    /** {@code sudo -n } on a shell that is not root, nothing on one that is. */
    private final String sudo;

    public BtrfsVolumeOperations(@NonNull HostShell shell) {
        this.shell = shell;
        this.sudo = shell.sudo();
    }

    @Override
    public void create(@NonNull String hostPath) {
        String quoted = HostShell.quote(hostPath);
        // A plain directory left by an earlier backend is NOT silently adopted: it would
        // take no quota and no snapshot, which is the failure this whole tier exists to
        // make impossible. Only an absent path or an existing subvolume is acceptable.
        run(this.sudo + "mkdir -p " + HostShell.quote(parentOf(hostPath))
            + " && { " + this.sudo + "btrfs subvolume show " + quoted + " >/dev/null 2>&1"
            + " || " + this.sudo + "btrfs subvolume create " + quoted + "; }", "volume_create_failed", hostPath);
    }

    @Override
    public void setQuota(@NonNull String hostPath, @Nullable Long bytes) {
        String quoted = HostShell.quote(hostPath);
        String limit = bytes == null || bytes <= 0 ? "none" : String.valueOf(bytes);
        run(this.sudo + "btrfs quota enable " + HostShell.quote(mountpointOf(hostPath)) + " >/dev/null 2>&1;"
            + " " + this.sudo + "btrfs qgroup limit " + limit + " " + quoted,
            "volume_quota_failed", hostPath);
    }

    @Override
    public long usage(@NonNull String hostPath) {
        HostShell.Result result = this.shell.run(
            this.sudo + "btrfs qgroup show --raw -f " + HostShell.quote(hostPath));
        if (!result.ok()) {
            Blast.log("VOLUMES: btrfs could not report usage of", hostPath, "-", result.text());
            return -1;
        }
        return referencedBytes(result.text());
    }

    /**
     * Parse the referenced-bytes column out of {@code btrfs qgroup show --raw -f}.
     *
     * AIDEV-NOTE: package-visible so the unit test reads the SAME parser the host lane
     * does. The header lines vary between btrfs-progs releases, so the parser keys on the
     * first line whose first field looks like a qgroup id, never on a line number.
     */
    static long referencedBytes(@NonNull String output) {
        for (String line : output.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 2 && fields[0].matches("\\d+/\\d+")) {
                try {
                    return Long.parseLong(fields[1]);
                } catch (NumberFormatException notANumber) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @Override
    public @NonNull String snapshot(@NonNull String hostPath, @NonNull String label) {
        String target = snapshotPathFor(hostPath, label);
        run(this.sudo + "mkdir -p " + HostShell.quote(parentOf(target))
            + " && " + this.sudo + "btrfs subvolume snapshot -r " + HostShell.quote(hostPath) + " "
            + HostShell.quote(target), "volume_snapshot_failed", hostPath);
        return target;
    }

    @Override
    public void deleteSnapshot(@NonNull String snapshotPath) {
        destroy(snapshotPath);
    }

    @Override
    public void destroy(@NonNull String hostPath) {
        String quoted = HostShell.quote(hostPath);
        run("if " + this.sudo + "btrfs subvolume show " + quoted + " >/dev/null 2>&1; then"
            + " " + this.sudo + "btrfs subvolume delete " + quoted + "; else " + this.sudo + "rm -rf " + quoted + "; fi",
            "volume_destroy_failed", hostPath);
    }

    @Override
    public void own(@NonNull String hostPath, int uid) {
        String quoted = HostShell.quote(hostPath);
        // The directory itself, and its mode: 0700 so the number that owns it is the only
        // identity that can read it. Nothing else on the host answers to that number.
        run(this.sudo + "chown " + uid + ":" + uid + " " + quoted + " && " + this.sudo + "chmod 0700 " + quoted,
            "volume_own_failed", hostPath);
    }

    /**
     * Where a snapshot of this volume lands: a sibling {@code .snapshots} tree that mirrors
     * the volume's own {@code <instance>/<name>} layout, so a snapshot is never inside the
     * volume it copies (which would make the next snapshot recursive).
     */
    public static @NonNull String snapshotPathFor(@NonNull String hostPath,
                                                  @NonNull String label) {
        String parent = parentOf(hostPath);
        String name = hostPath.substring(hostPath.lastIndexOf('/') + 1);
        String stamp = Instant.now().toString().replace(':', '-').replace('.', '-');
        return parentOf(parent) + "/" + SNAPSHOT_DIRECTORY + "/"
            + parent.substring(parent.lastIndexOf('/') + 1) + "/" + name + "@"
            + sanitize(label) + "-" + stamp;
    }

    /** The btrfs filesystem a path lives on; the whole volume root is one filesystem. */
    private static @NonNull String mountpointOf(@NonNull String hostPath) {
        return parentOf(parentOf(hostPath));
    }

    private static @NonNull String parentOf(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static @NonNull String sanitize(@NonNull String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return cleaned.isBlank() ? "snapshot" : cleaned;
    }

    private void run(@NonNull String script, @NonNull String violation,
                     @NonNull String hostPath) {
        HostShell.Result result = this.shell.run(script);
        if (!result.ok()) {
            throw Violations.ofForm(Microcopy.of(violation).withFilter("scope", "violations")
                .withArg("path", hostPath)
                .withArg("backend", VolumeBackend.BTRFS.label())
                .withArg("reason", result.text()));
        }
    }
}
