package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.util.PosixPaths;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        run(privileged(PrivilegedHelper.Verb.VOLUME_CREATE,
            this.sudo + "mkdir -p " + HostShell.quote(PosixPaths.parentOf(hostPath))
                + " && { " + this.sudo + "btrfs subvolume show " + quoted + " >/dev/null 2>&1"
                + " || " + this.sudo + "btrfs subvolume create " + quoted + "; }", hostPath),
            "volume_create_failed", hostPath);
    }

    @Override
    public void setQuota(@NonNull String hostPath, @Nullable Long bytes) {
        String quoted = HostShell.quote(hostPath);
        String limit = bytes == null || bytes <= 0 ? "none" : String.valueOf(bytes);
        run(privileged(PrivilegedHelper.Verb.VOLUME_QUOTA,
            this.sudo + "btrfs quota enable " + HostShell.quote(mountpointOf(hostPath)) + " >/dev/null 2>&1;"
                + " " + this.sudo + "btrfs qgroup limit " + limit + " " + quoted, hostPath, limit),
            "volume_quota_failed", hostPath);
    }

    @Override
    public long usage(@NonNull String hostPath) {
        HostShell.Result result = this.shell.run(privileged(PrivilegedHelper.Verb.VOLUME_USAGE,
            this.sudo + "btrfs qgroup show --raw -f " + HostShell.quote(hostPath), hostPath));
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
        run(privileged(PrivilegedHelper.Verb.VOLUME_SNAPSHOT,
            this.sudo + "mkdir -p " + HostShell.quote(PosixPaths.parentOf(target))
                + " && " + this.sudo + "btrfs subvolume snapshot -r " + HostShell.quote(hostPath) + " "
                + HostShell.quote(target), hostPath, target),
            "volume_snapshot_failed", hostPath);
        return target;
    }

    @Override
    public void deleteSnapshot(@NonNull String snapshotPath) {
        destroy(snapshotPath);
    }

    @Override
    public void destroy(@NonNull String hostPath) {
        String quoted = HostShell.quote(hostPath);
        run(privileged(PrivilegedHelper.Verb.VOLUME_DESTROY,
            "if " + this.sudo + "btrfs subvolume show " + quoted + " >/dev/null 2>&1; then"
                + " " + this.sudo + "btrfs subvolume delete " + quoted + "; else " + this.sudo + "rm -rf "
                + quoted + "; fi",
            hostPath),
            "volume_destroy_failed", hostPath);
    }

    @Override
    public void pruneOwner(@NonNull String ownerPath) {
        String mirror = PosixPaths.parentOf(ownerPath) + "/" + SNAPSHOT_DIRECTORY + "/" + PosixPaths.nameOf(ownerPath);
        run(privileged(PrivilegedHelper.Verb.VOLUME_PRUNE, removedWhenEmpty(ownerPath) + " && "
                + removedWhenEmpty(mirror), ownerPath),
            "volume_destroy_failed", ownerPath);
    }

    /** {@code rmdir} of a real directory that holds nothing; an absent one, a symlink or a full one is left alone. */
    private @NonNull String removedWhenEmpty(@NonNull String path) {
        String quoted = HostShell.quote(path);
        return "if [ -d " + quoted + " ] && [ ! -L " + quoted + " ]; then " + this.sudo
            + "rmdir --ignore-fail-on-non-empty " + quoted + "; fi";
    }

    @Override
    public void own(@NonNull String hostPath, int uid) {
        String quoted = HostShell.quote(hostPath);
        // The directory itself, and its mode: 0700 so the number that owns it is the only
        // identity that can read it. Nothing else on the host answers to that number.
        run(privileged(PrivilegedHelper.Verb.VOLUME_OWN,
            this.sudo + "chown " + uid + ":" + uid + " " + quoted + " && " + this.sudo + "chmod 0700 " + quoted,
            hostPath, String.valueOf(uid)),
            "volume_own_failed", hostPath);
    }

    /**
     * AIDEV-NOTE: the tar reads a read-only snapshot taken for it, never the live subvolume: the workload keeps
     * running through a backup, and the snapshot is the one point in time the whole volume is read at (crash
     * consistent, like the daemons' own exports). The snapshot goes again whatever the read did.
     */
    @Override
    public long archive(@NonNull String hostPath, @NonNull Path target, long maxBytes) {
        String snapshot = snapshot(hostPath, "backup");
        try {
            HostShell.Result result = this.shell.runReading(privileged(PrivilegedHelper.Verb.VOLUME_READ,
                this.sudo + "tar --numeric-owner -C " + HostShell.quote(snapshot) + " -cf - .", snapshot),
                target, maxBytes, HostShell.PAYLOAD_TIMEOUT_SECONDS);
            if (!result.ok()) {
                throw failure("volume_archive_failed", hostPath, result.text());
            }
            return Files.size(target);
        } catch (IOException unreadable) {
            throw failure("volume_archive_failed", hostPath, String.valueOf(unreadable.getMessage()));
        } finally {
            try {
                deleteSnapshot(snapshot);
            } catch (RuntimeException cleanupFailed) {
                Blast.log("VOLUMES: could not remove the backup snapshot", snapshot, "-", cleanupFailed.getMessage());
            }
        }
    }

    @Override
    public void restore(@NonNull String hostPath, @NonNull Path source, @Nullable Integer ownerUid) {
        String quoted = HostShell.quote(hostPath);
        String legacy = this.sudo + "tar --numeric-owner -C " + quoted + " -xf -"
            + (ownerUid != null ? " && " + this.sudo + "chown -R -h " + ownerUid + ":" + ownerUid + " " + quoted : "");
        HostShell.Result result = this.shell.runFeeding(privileged(PrivilegedHelper.Verb.VOLUME_WRITE, legacy,
            hostPath, ownerUid != null ? String.valueOf(ownerUid) : "keep"), source, HostShell.PAYLOAD_TIMEOUT_SECONDS);
        if (!result.ok()) {
            throw failure("volume_restore_failed", hostPath, result.text());
        }
    }

    @Override
    public long availableBytes(@NonNull String path) {
        HostShell.Result result = this.shell.run("df -B1 --output=avail " + HostShell.quote(path));
        String[] lines = result.text().trim().split("\n");
        try {
            if (result.ok()) {
                return Long.parseLong(lines[lines.length - 1].trim());
            }
        } catch (NumberFormatException unparseable) {
            // unanswerable, like a failed df
        }
        Blast.log("VOLUMES: could not measure the free space of", path, "-", result.text());
        return -1;
    }

    /**
     * Where a snapshot of this volume lands: a sibling {@code .snapshots} tree that mirrors
     * the volume's own {@code <instance>/<name>} layout, so a snapshot is never inside the
     * volume it copies (which would make the next snapshot recursive).
     */
    public static @NonNull String snapshotPathFor(@NonNull String hostPath,
                                                  @NonNull String label) {
        String parent = PosixPaths.parentOf(hostPath);
        String name = PosixPaths.nameOf(hostPath);
        String stamp = Now.instant().toString().replace(':', '-').replace('.', '-');
        return PosixPaths.parentOf(parent) + "/" + SNAPSHOT_DIRECTORY + "/"
            + PosixPaths.nameOf(parent) + "/" + name + "@"
            + sanitize(label) + "-" + stamp;
    }

    /** The btrfs filesystem a path lives on; the whole volume root is one filesystem. */
    private static @NonNull String mountpointOf(@NonNull String hostPath) {
        return PosixPaths.parentOf(PosixPaths.parentOf(hostPath));
    }


    private static @NonNull String sanitize(@NonNull String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return cleaned.isBlank() ? "snapshot" : cleaned;
    }

    /**
     * The script one operation runs: the bare commands on a root shell, and on an unprivileged
     * one the {@link PrivilegedHelper} verb, falling back to the sudo-elevated legacy commands
     * on a host whose helper is not installed yet.
     *
     * @param legacy the operation's commands, already prefixed with {@link #sudo}
     */
    private @NonNull String privileged(PrivilegedHelper.@NonNull Verb verb, @NonNull String legacy,
                                       @NonNull String... args) {
        return this.shell.elevated() ? legacy : PrivilegedHelper.snippet(verb, legacy, args);
    }

    private void run(@NonNull String script, @NonNull String violation,
                     @NonNull String hostPath) {
        HostShell.Result result = this.shell.run(script);
        if (!result.ok()) {
            throw failure(violation, hostPath, result.text());
        }
    }

    private static @NonNull Violations failure(@NonNull String violation, @NonNull String hostPath,
                                               @NonNull String reason) {
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(violation)
            .withArg("path", hostPath)
            .withArg("backend", VolumeBackend.BTRFS.label())
            .withArg("reason", reason));
    }
}
