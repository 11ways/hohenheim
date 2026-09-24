package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.process.BoundedProcess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Backup target over SSH to an INVENTORIED HOST RECORD: the remote/object
 * implementation of the target seam. Bytes stream through the ssh process with a
 * bounded buffer (never a whole-archive byte[]), writes land on a {@code .part}
 * staging name and commit via remote {@code mv}, and verification asks the REMOTE
 * host for the committed file's sha256 -- the artifact the restore would read, not
 * the bytes we sent.
 *
 * AIDEV-NOTE: the destination is a {@code servers} row, never a typed-in
 * {@code user@host} with a pasted pin beside it. Two authorities over "is this remote
 * host the one we think it is" is one too many, and the pasted one was strictly
 * weaker: nothing forced the operator to have verified the line they pasted, where a
 * host record walks scan / display / out-of-band confirm / pin and QUARANTINES on a
 * later mismatch. Every exchange re-reads the row and re-passes the gate, so a host
 * quarantined mid-backup stops the very next command instead of at the next operation.
 *
 * Trust therefore rides {@link HostKeys#sshArgv} -- THE inventoried-host argv, which
 * also brings the per-host client identity and {@code IdentitiesOnly=yes}. There is no
 * argv construction in this class at all, which is how "no ambient known_hosts, ever"
 * is true BY CONSTRUCTION here rather than by inspection.
 */
public final class SshBackupTarget implements BackupTarget {

    static final String STAGING_SUFFIX = ".part";

    /** Keys are controller-generated; anything outside this set is refused, not quoted. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._/-]+");

    /** How long an exchange may go without moving a byte before it is abandoned. */
    private static final long COMMAND_IDLE_TIMEOUT_MS = 600_000;

    private static final int STDERR_CAP_CHARS = 64 * 1024;

    private final int serverId;
    private final @NonNull String basePath;

    /**
     * @param serverId the {@code servers} row this destination lives on
     * @param basePath absolute remote directory backups live under
     */
    public SshBackupTarget(int serverId, @NonNull String basePath) {
        this.serverId = serverId;
        this.basePath = normalizedBase(basePath);
    }

    /**
     * The base path without trailing slashes ("/" stays "/").
     *
     * AIDEV-NOTE: {@link #list} matches {@code find}'s output against {@code base + "/"}, so a
     * configured {@code /backups/} matched nothing ("/backups//x" never appears): list()
     * answered empty for a target full of archives and retention never pruned anything.
     */
    public static @NonNull String normalizedBase(@NonNull String basePath) {
        String trimmed = basePath.trim();
        int end = trimmed.length();
        while (end > 1 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * The destination host, re-read and re-gated per exchange.
     *
     * @throws Violations when the host vanished, is not an ssh host, is unconfirmed or
     *                    is quarantined
     */
    private @NonNull Row destination() {
        Row server = Models.get(ServerModel.class).findById(this.serverId);
        if (server == null) {
            throw Violations.ofForm(Microcopy.of("backup_target_host_missing")
                .withFilter("scope", "violations").withArg("id", this.serverId));
        }
        HostAdmission.requireBackupDestination(server);
        return server;
    }

    /**
     * Pass the destination gate without connecting.
     *
     * @throws Violations naming why this host may not receive backups
     */
    public void requireUsableDestination() {
        destination();
    }

    @Override
    public void healthCheck() throws IOException {
        String probe = quoted(this.basePath + "/.hohenheim-target-probe");
        run("mkdir -p " + quoted(this.basePath)
            + " && touch " + probe + " && rm -f " + probe + " && echo HOHENHEIM_TARGET_OK",
            null, null);
    }

    @Override
    public void store(@NonNull String key, @NonNull Path file) throws IOException {
        String committed = remotePath(key);
        String staging = committed + STAGING_SUFFIX;
        String directory = parentOf(committed);
        try (InputStream in = Files.newInputStream(file)) {
            run("mkdir -p " + quoted(directory) + " && cat > " + quoted(staging), in, null);
        } catch (IOException error) {
            bestEffort("rm -f " + quoted(staging));
            throw error;
        }
        // Commit is a separate exchange: the rename happens only after the stream
        // above finished cleanly, so a killed upload leaves ONLY the .part name. The
        // staged bytes are synced BEFORE the rename and the directory after it, so a
        // remote crash cannot leave the committed name over unwritten blocks. `sync FILE`
        // is coreutils; a sync without it (busybox) falls back to syncing everything.
        try {
            run(syncOf(staging) + " && mv " + quoted(staging) + " " + quoted(committed)
                + " && " + syncOf(directory), null, null);
        } catch (IOException error) {
            bestEffort("rm -f " + quoted(staging));
            throw error;
        }
    }

    @Override
    public @NonNull String storedSha256(@NonNull String key) throws IOException {
        String output = new String(
            run("sha256sum -b " + quoted(remotePath(key)), null, null),
            StandardCharsets.UTF_8).trim();
        int space = output.indexOf(' ');
        String sha = space > 0 ? output.substring(0, space) : output;
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IOException("Remote sha256sum answered unexpectedly: " + output);
        }
        return sha;
    }

    @Override
    public void retrieve(@NonNull String key, @NonNull Path destination) throws IOException {
        Path staging = destination.resolveSibling(destination.getFileName() + ".retrieving");
        try (OutputStream out = Files.newOutputStream(staging,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            runStreaming("cat " + quoted(remotePath(key)), null, out);
        } catch (IOException error) {
            Files.deleteIfExists(staging);
            throw error;
        }
        Files.move(staging, destination,
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * AIDEV-NOTE: the remote command answers YES/NO and exits 0 either way, so the only
     * IOException left is a transport failure and it PROPAGATES. The old shape ran
     * {@code test -f} and mapped every IOException to false, which reported "the
     * artifact is not there" for an unreachable host -- a definite negative nobody
     * observed, on the exact question a retention sweep acts on.
     */
    @Override
    public boolean exists(@NonNull String key) throws IOException {
        String answer = new String(
            run("test -f " + quoted(remotePath(key)) + " && echo YES || echo NO", null, null),
            StandardCharsets.UTF_8).trim();
        if (!"YES".equals(answer) && !"NO".equals(answer)) {
            throw new IOException("Remote existence probe answered unexpectedly: " + answer);
        }
        return "YES".equals(answer);
    }

    /**
     * AIDEV-NOTE: the remote command is guarded by {@code test -d} and exits 0 on an absent
     * base directory, so "nothing uploaded yet" is an empty list while an unreachable host is
     * still an IOException. Filtering happens HERE, on full keys, rather than through a remote
     * glob: a shell glob would be one quoting mistake away from listing the whole filesystem.
     */
    @Override
    public @NonNull List<String> list(@NonNull String prefix) throws IOException {
        String base = quoted(this.basePath);
        String output = new String(
            run("if [ -d " + base + " ]; then find " + base + " -type f; fi", null, null),
            StandardCharsets.UTF_8);
        List<String> keys = new ArrayList<>();
        for (String line : output.split("\n")) {
            String path = line.trim();
            if (path.isEmpty() || !path.startsWith(this.basePath + "/")) {
                continue;
            }
            String key = path.substring(this.basePath.length() + 1);
            if (key.startsWith(prefix) && !key.endsWith(STAGING_SUFFIX)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        String committed = remotePath(key);
        run("rm -f " + quoted(committed) + " " + quoted(committed + STAGING_SUFFIX), null, null);
    }

    // -- plumbing -------------------------------------------------------------

    private @NonNull String remotePath(@NonNull String key) throws IOException {
        if (!SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IOException("Backup key '" + key + "' is not a safe remote path");
        }
        return this.basePath + "/" + key;
    }

    private byte @NonNull [] run(@NonNull String remoteCommand, @Nullable InputStream stdin,
                                 @Nullable OutputStream stdout) throws IOException {
        return runStreaming(remoteCommand, stdin, stdout);
    }

    /**
     * AIDEV-NOTE: swallows the REFUSAL too (a host quarantined between the failed write
     * and this cleanup), because this runs inside a catch block whose original error is
     * the one that must reach the caller. The .part suffix marks the debris regardless.
     */
    private void bestEffort(@NonNull String remoteCommand) {
        try {
            runStreaming(remoteCommand, null, null);
        } catch (IOException | RuntimeException ignored) {
            // cleanup is best effort; the caller rethrows the failure that got us here
        }
    }

    private byte @NonNull [] runStreaming(@NonNull String remoteCommand,
                                          @Nullable InputStream stdin,
                                          @Nullable OutputStream stdout) throws IOException {
        List<String> argv = HostKeys.sshArgv(destination(), List.of(remoteCommand));
        return exchange(argv, stdin, stdout, remoteCommand);
    }

    /**
     * AIDEV-NOTE: the bound is IDLE time ({@link BoundedProcess#stream}): an archive's
     * upload or download takes as long as its size needs, so a wall clock would cut a
     * large backup, while an ssh that stopped moving bytes is abandoned. The exchange this
     * replaced read stdout to its end before ever consulting the clock, so a wedged ssh
     * held the backup job forever.
     */
    private static byte @NonNull [] exchange(@NonNull List<String> argv,
                                             @Nullable InputStream stdin,
                                             @Nullable OutputStream stdout,
                                             @NonNull String what) throws IOException {
        ByteArrayOutputStream captured = stdout == null ? new ByteArrayOutputStream() : null;
        BoundedProcess.Result result;
        try {
            result = BoundedProcess.stream(new ProcessBuilder(argv), stdin,
                stdout != null ? stdout : captured, COMMAND_IDLE_TIMEOUT_MS, STDERR_CAP_CHARS);
        } catch (InterruptedIOException interrupted) {
            throw new IOException("SSH backup-target command interrupted: " + what, interrupted);
        }
        if (result.timedOut()) {
            throw new IOException("SSH backup-target command timed out: " + what);
        }
        if (!result.succeeded()) {
            throw new IOException("SSH backup-target command failed (exit "
                + result.exitCode() + "): " + what + " -- " + result.stderr().trim());
        }
        return captured != null ? captured.toByteArray() : new byte[0];
    }

    /** A remote command flushing one path to disk, degrading to a whole-system sync. */
    private static @NonNull String syncOf(@NonNull String path) {
        return "{ sync -- " + quoted(path) + " 2>/dev/null || sync; }";
    }

    /** Single-quote a remote path for the remote shell ('\'' escape for embedded quotes). */
    private static @NonNull String quoted(@NonNull String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static @NonNull String parentOf(@NonNull String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }
}
