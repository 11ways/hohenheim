package be.elevenways.hohenheim.server.backup;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Backup target over SSH to another host: the remote/object implementation of the
 * target seam. Bytes stream through the ssh process with a bounded buffer (never a
 * whole-archive byte[]), writes land on a {@code .part} staging name and commit via
 * remote {@code mv}, and verification asks the REMOTE host for the committed file's
 * sha256 -- the artifact the restore would read, not the bytes we sent.
 *
 * Trust: {@code BatchMode=yes} + {@code StrictHostKeyChecking=yes} always. With a
 * pinned host key in the target settings, verification runs against ONLY that pin
 * (a temp known_hosts file); without one, the OS user's own known_hosts must already
 * trust the host. There is deliberately no accept-new anywhere -- silent TOFU is the
 * exact hole the host-key-pinning wave closed.
 */
public final class SshBackupTarget implements BackupTarget {

    static final String STAGING_SUFFIX = ".part";

    /** Keys are controller-generated; anything outside this set is refused, not quoted. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._/-]+");

    private static final long COMMAND_TIMEOUT_MS = 600_000;

    private final @NonNull String target;
    private final @NonNull String basePath;
    private final @Nullable String pinnedHostKey;

    /**
     * @param target        {@code user@host} (optionally {@code host} alone)
     * @param basePath      absolute remote directory backups live under
     * @param pinnedHostKey a full known_hosts line to pin, or null to use the OS
     *                      user's known_hosts (which must already trust the host)
     */
    public SshBackupTarget(@NonNull String target, @NonNull String basePath,
                           @Nullable String pinnedHostKey) {
        this.target = target;
        this.basePath = basePath;
        this.pinnedHostKey = pinnedHostKey == null || pinnedHostKey.isBlank()
            ? null : pinnedHostKey.trim();
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
        // above finished cleanly, so a killed upload leaves ONLY the .part name.
        try {
            run("mv " + quoted(staging) + " " + quoted(committed), null, null);
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

    @Override
    public boolean exists(@NonNull String key) throws IOException {
        try {
            run("test -f " + quoted(remotePath(key)) + " && echo YES", null, null);
            return true;
        } catch (IOException e) {
            return false;
        }
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

    private void bestEffort(@NonNull String remoteCommand) {
        try {
            runStreaming(remoteCommand, null, null);
        } catch (IOException ignored) {
            // cleanup is best effort; the .part suffix marks the debris regardless
        }
    }

    private byte @NonNull [] runStreaming(@NonNull String remoteCommand,
                                          @Nullable InputStream stdin,
                                          @Nullable OutputStream stdout) throws IOException {
        Path knownHosts = null;
        try {
            List<String> argv = new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=yes"));
            if (this.pinnedHostKey != null) {
                knownHosts = Files.createTempFile("hohenheim-target-hostkey", ".tmp");
                Files.writeString(knownHosts, this.pinnedHostKey + "\n");
                argv.add("-o");
                argv.add("UserKnownHostsFile=" + knownHosts);
            }
            argv.add(this.target);
            argv.add("--");
            argv.add(remoteCommand);
            return exchange(argv, stdin, stdout, remoteCommand);
        } finally {
            if (knownHosts != null) {
                try {
                    Files.deleteIfExists(knownHosts);
                } catch (IOException ignored) {
                    // temp cleanup
                }
            }
        }
    }

    private static byte @NonNull [] exchange(@NonNull List<String> argv,
                                             @Nullable InputStream stdin,
                                             @Nullable OutputStream stdout,
                                             @NonNull String what) throws IOException {
        Process process = new ProcessBuilder(argv).start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(stderr);
            } catch (IOException ignored) {
                // process gone
            }
        });
        drain.setDaemon(true);
        drain.start();
        ByteArrayOutputStream captured = stdout == null ? new ByteArrayOutputStream() : null;
        try {
            Thread writer = null;
            if (stdin != null) {
                writer = new Thread(() -> {
                    try (OutputStream processIn = process.getOutputStream()) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = stdin.read(buffer)) >= 0) {
                            processIn.write(buffer, 0, read);
                        }
                    } catch (IOException ignored) {
                        // the exit code is the authority on failure
                    }
                });
                writer.setDaemon(true);
                writer.start();
            } else {
                process.getOutputStream().close();
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            InputStream processOut = process.getInputStream();
            while ((read = processOut.read(buffer)) >= 0) {
                if (stdout != null) {
                    stdout.write(buffer, 0, read);
                } else {
                    captured.write(buffer, 0, read);
                }
            }
            if (writer != null) {
                writer.join(COMMAND_TIMEOUT_MS);
            }
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("SSH backup-target command timed out: " + what);
            }
            if (process.exitValue() != 0) {
                drain.join(2000);
                throw new IOException("SSH backup-target command failed (exit "
                    + process.exitValue() + "): " + what + " -- "
                    + new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim());
            }
            return captured != null ? captured.toByteArray() : new byte[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("SSH backup-target command interrupted: " + what);
        } finally {
            process.destroyForcibly();
        }
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
