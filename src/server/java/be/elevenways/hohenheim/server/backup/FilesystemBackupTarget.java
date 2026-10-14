package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.server.io.DurableFiles;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
        try {
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = openStaging(staging)) {
                copyBounded(in, out);
            }
            DurableFiles.forceFile(staging);
            Files.move(staging, committed,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            DurableFiles.forceDirectory(committed.getParent());
        } catch (IOException error) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // best effort; the suffix marks it as never-committed either way
            }
            throw error;
        }
    }

    /** Opens the staging name, recreating its folder while a concurrent {@link #delete} prunes it in between. */
    private static @NonNull OutputStream openStaging(@NonNull Path staging) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.createDirectories(staging.getParent());
                return Files.newOutputStream(staging,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (NoSuchFileException | FileAlreadyExistsException pruned) {
                // NoSuchFile: the folder went before the open; FileAlreadyExists: createDirectories found it, then
                // saw it gone when it checked that it is a directory.
                if (attempt >= CREATE_ATTEMPTS) {
                    throw pruned;
                }
            }
        }
    }

    @Override
    public @NonNull String storedSha256(@NonNull String key) throws IOException {
        Path committed = resolve(key);
        if (!Files.isRegularFile(committed)) {
            throw new IOException("Backup target holds no committed artifact at " + committed);
        }
        return SecureTokens.sha256Hex(committed);
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
    public @NonNull List<String> list(@NonNull String prefix) throws IOException {
        Path base = this.root.normalize();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String key = base.relativize(path).toString();
                if (key.startsWith(prefix) && !key.endsWith(STAGING_SUFFIX)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        Path committed = resolve(key);
        Files.deleteIfExists(committed);
        Files.deleteIfExists(stagingOf(committed));
        String namespace = BackupTarget.namespaceOf(key);
        if (namespace == null) {
            return;
        }
        Path top = resolve(namespace);
        if (top.equals(this.root.normalize()) || !Files.isDirectory(top)) {
            return;
        }
        List<Path> folders;
        try (Stream<Path> walk = Files.walk(top)) {
            folders = walk.filter(Files::isDirectory).sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .toList();
        } catch (IOException | UncheckedIOException walkRaced) {
            return;   // a concurrent delete pruned part of it first; that delete swept it
        }
        // Deepest first, so a folder emptied by its children goes too; Files.delete removes only an empty folder.
        for (Path folder : folders) {
            try {
                Files.delete(folder);
            } catch (IOException kept) {
                // not empty, or already pruned by a concurrent delete
            }
        }
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

}
