package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What every implemented backend does alike over a host shell: owning, archiving and restoring a volume's folder,
 * measuring its filesystem, and running a privileged step through the helper; a member supplies what its filesystem
 * does differently (create, quota, usage, snapshots, destroy).
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
abstract sealed class ShellVolumeOperations implements VolumeOperations
        permits BtrfsVolumeOperations, ZfsVolumeOperations {

    protected final HostShell shell;

    /** {@code sudo -n } on a shell that is not root, nothing on one that is. */
    protected final String sudo;

    protected ShellVolumeOperations(@NonNull HostShell shell) {
        this.shell = shell;
        this.sudo = shell.sudo();
    }

    /** @return the backend this member drives, named in its failures */
    protected abstract @NonNull VolumeBackend backend();

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
     * AIDEV-NOTE: the tar reads a read-only snapshot taken for it, never the live volume: the workload keeps running
     * through a backup, and the snapshot is the one point in time the whole volume is read at (crash consistent, like
     * the daemons' own exports). The snapshot goes again whatever the read did.
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
    public @Nullable DiskSpace space(@NonNull String path) {
        return VolumeOperations.spaceOver(this.shell, path);
    }

    /**
     * The script one operation runs: the bare commands on a root shell, and on an unprivileged one the
     * {@link PrivilegedHelper} verb, falling back to the sudo-elevated legacy commands on a host whose helper is not
     * installed yet.
     *
     * @param legacy the operation's commands, already prefixed with {@link #sudo}
     */
    protected final @NonNull String privileged(PrivilegedHelper.@NonNull Verb verb, @NonNull String legacy,
                                               @NonNull String... args) {
        return this.shell.elevated() ? legacy : PrivilegedHelper.snippet(verb, legacy, args);
    }

    protected final void run(@NonNull String script, @NonNull String violation, @NonNull String hostPath) {
        HostShell.Result result = this.shell.run(script);
        if (!result.ok()) {
            throw failure(violation, hostPath, result.text());
        }
    }

    protected final @NonNull Violations failure(@NonNull String violation, @NonNull String hostPath,
                                                @NonNull String reason) {
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(violation)
            .withArg("path", hostPath)
            .withArg("backend", backend().label())
            .withArg("reason", reason));
    }
}
