package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Backup target over a directory: staging {@code .part} write, fsync, atomic rename.
 * OFF-HOST HONESTY: a directory on the controller (or a combined control+compute
 * host) is NOT a different failure domain -- this kind is the floor for a mounted
 * remote filesystem or a directory that is itself backed up off-host, and its admin
 * help says so.
 */
public final class FilesystemBackupTarget implements BackupTarget {

    static final String STAGING_SUFFIX = ".part";

    private final @NonNull Path root;

    public FilesystemBackupTarget(@NonNull Path root) {
        this.root = root;
    }

    @Override
    public void healthCheck() throws IOException {
        Files.createDirectories(this.root);
        if (!Files.isWritable(this.root)) {
            throw new IOException("Backup target directory " + this.root + " is not writable");
        }
        // A real write proves more than isWritable (read-only mounts, quota).
        Path probe = this.root.resolve(".hohenheim-target-probe");
        Files.writeString(probe, "ok");
        Files.deleteIfExists(probe);
    }

    @Override
    public void store(@NonNull String key, @NonNull Path file) throws IOException {
        Path committed = resolve(key);
        Path staging = stagingOf(committed);
        Files.createDirectories(committed.getParent());
        try {
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = Files.newOutputStream(staging,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(in, out);
            }
            force(staging);
            Files.move(staging, committed,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // best effort; the suffix marks it as never-committed either way
            }
            throw error;
        }
    }

    @Override
    public @NonNull String storedSha256(@NonNull String key) throws IOException {
        Path committed = resolve(key);
        if (!Files.isRegularFile(committed)) {
            throw new IOException("Backup target holds no committed artifact at " + committed);
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(committed)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return SecureTokens.hex(digest.digest());
    }

    @Override
    public void retrieve(@NonNull String key, @NonNull Path destination) throws IOException {
        Files.copy(resolve(key), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(@NonNull String key) throws IOException {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        Path committed = resolve(key);
        Files.deleteIfExists(committed);
        Files.deleteIfExists(stagingOf(committed));
    }

    /** Resolve a key under root, refusing traversal out of it. */
    private @NonNull Path resolve(@NonNull String key) throws IOException {
        Path resolved = this.root.resolve(key).normalize();
        if (!resolved.startsWith(this.root.normalize())) {
            throw new IOException("Backup key '" + key + "' escapes the target directory");
        }
        return resolved;
    }

    private static Path stagingOf(Path committed) {
        return committed.resolveSibling(committed.getFileName() + STAGING_SUFFIX);
    }

    private static void copyBounded(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
