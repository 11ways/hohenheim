package be.elevenways.hohenheim.test.instance;

import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.PutOptions;
import be.elevenways.zenit.server.storage.StoredObject;
import be.elevenways.zenit.test.support.RecordingStorage;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A folder storage whose uploads die halfway, as a network death mid-transfer: the put streams half the bytes into the
 * storage's own staging and the transfer then fails, so what the storage leaves behind is the storage's to clean.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
final class DyingUploadStorage extends RecordingStorage {

    /** The key the last upload was attempted under. */
    final AtomicReference<String> attemptedKey = new AtomicReference<>();

    private final Path root;

    DyingUploadStorage(@NonNull Path root) {
        super(new LocalDiskStorageAdapter(root));
        this.root = root;
    }

    @Override
    public @NonNull StoredObject put(@NonNull String key, @NonNull InputStream data, @NonNull PutOptions options) {
        this.attemptedKey.set(key);
        return super.put(key, new FilterInputStream(data) {
            private long left = Math.max(1, options.contentLength() / 2);

            @Override
            public int read() throws IOException {
                return this.left-- > 0 ? super.read() : die();
            }

            @Override
            public int read(byte @NonNull [] buffer, int at, int count) throws IOException {
                if (this.left <= 0) {
                    return die();
                }
                int read = super.read(buffer, at, (int) Math.min(count, this.left));
                this.left -= Math.max(0, read);
                return read;
            }

            private int die() throws IOException {
                throw new IOException("simulated network failure mid-upload");
            }
        }, options);
    }

    /** @return every file the storage's staging still holds: none once a failed upload was cleaned up */
    @NonNull List<Path> stagingDebris() {
        Path staging = this.root.resolve(".tmp");
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(staging)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
