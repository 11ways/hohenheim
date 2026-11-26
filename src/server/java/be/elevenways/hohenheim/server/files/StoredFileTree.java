package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.zenit.server.io.FilesystemNodeKind;
import be.elevenways.zenit.server.storage.PutOptions;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StorageException;
import be.elevenways.zenit.server.storage.StoredObject;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A site's stored files as the file manager's tree: the folder of keys under one prefix of a {@link StorageAdapter},
 * its path "/" the prefix itself, every path below it the key of the same spelling.
 *
 * AIDEV-NOTE: every path reaching this tree already passed the file manager's containment ({@link InstanceFilePath},
 * the service's walk); the storage key grammar refuses what a path may still spell but a key may not (a dot-leading
 * name, a backslash), reported as a refused path. Stored objects keep no permission bits and no times of a person's
 * choosing: a file reads 0644 and a folder 0755, and a mode or time change is accepted and changes nothing, because an
 * SFTP client sets both after every upload and a refusal there would fail the upload it follows. No object is ever a
 * link, so the containment walk can never meet one here.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StoredFileTree implements InstanceFileSupport {

    private static final String FILE_MODE = "0644";
    private static final String FOLDER_MODE = "0755";

    private final @NonNull StorageAdapter storage;
    private final @NonNull String prefix;

    /** @param prefix the folder key every file of the tree is stored under */
    StoredFileTree(@NonNull StorageAdapter storage, @NonNull String prefix) {
        this.storage = storage;
        this.prefix = prefix;
    }

    /** @return the storage the tree's files are kept in */
    public @NonNull StorageAdapter storage() {
        return this.storage;
    }

    /** @return the key a tree path names: the prefix for "/", else the prefix and the path */
    public @NonNull String keyOf(@NonNull String path) {
        return "/".equals(path) ? this.prefix : this.prefix + path;
    }

    /** @return the stored object at the path, null when none is there */
    public @Nullable StoredObject objectAt(@NonNull String path) throws IOException {
        if ("/".equals(path)) {
            return null;
        }
        return call(() -> this.storage.stat(this.keyOf(path)));
    }

    /** @return the bytes every object of the tree holds together */
    long storedBytes() throws IOException {
        return call(() -> this.bytesUnder(this.prefix));
    }

    private long bytesUnder(@NonNull String folder) {
        StorageAdapter.Listing listing = this.storage.children(folder);
        long bytes = 0;
        for (StoredObject object : listing.objects()) {
            bytes += object.size();
        }
        for (String name : listing.folders()) {
            bytes += this.bytesUnder(folder + "/" + name);
        }
        return bytes;
    }

    @Override
    public @NonNull List<Entry> listDirectory(@NonNull String handle, @NonNull String path, int maxEntries)
            throws IOException {
        StorageAdapter.Listing listing = call(() -> this.storage.children(this.keyOf(path)));
        if (listing.objects().size() + listing.folders().size() > maxEntries) {
            throw new IOException("The folder holds more than " + maxEntries + " entries");
        }
        List<Entry> entries = new ArrayList<>();
        for (String folder : listing.folders()) {
            entries.add(new Entry(folder, FilesystemNodeKind.DIRECTORY, 0, 0, FOLDER_MODE));
        }
        for (StoredObject object : listing.objects()) {
            String name = object.key().substring(object.key().lastIndexOf('/') + 1);
            entries.add(new Entry(name, FilesystemNodeKind.FILE, object.size(),
                object.lastModified().getEpochSecond(), FILE_MODE));
        }
        return entries;
    }

    @Override
    public @NonNull Entry stat(@NonNull String handle, @NonNull String path) throws IOException {
        String name = "/".equals(path) ? "/" : path.substring(path.lastIndexOf('/') + 1);
        if ("/".equals(path)) {
            return new Entry(name, FilesystemNodeKind.DIRECTORY, 0, 0, FOLDER_MODE);
        }
        StoredObject object = this.objectAt(path);
        if (object != null) {
            return new Entry(name, FilesystemNodeKind.FILE, object.size(), object.lastModified().getEpochSecond(),
                FILE_MODE);
        }
        if (call(() -> this.storage.folderExists(this.keyOf(path)))) {
            return new Entry(name, FilesystemNodeKind.DIRECTORY, 0, 0, FOLDER_MODE);
        }
        throw new FileNotFoundException(path);
    }

    @Override
    public byte @NonNull [] readFile(@NonNull String handle, @NonNull String path, long maxBytes) throws IOException {
        try (InputStream in = this.open(path, 0, maxBytes + 1)) {
            byte[] content = in.readAllBytes();
            if (content.length > maxBytes) {
                throw new IOException("The file is larger than " + maxBytes + " bytes");
            }
            return content;
        }
    }

    @Override
    public long readFileTo(@NonNull String handle, @NonNull String path, @NonNull Path out, long maxBytes)
            throws IOException {
        long copied;
        try (InputStream in = this.open(path, 0, maxBytes + 1);
             OutputStream target = Files.newOutputStream(out, StandardOpenOption.CREATE,
                 StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            copied = in.transferTo(target);
        }
        if (copied > maxBytes) {
            Files.deleteIfExists(out);
            throw new IOException("The file is larger than " + maxBytes + " bytes");
        }
        return copied;
    }

    /**
     * The stored bytes of a file from {@code offset}, at most {@code length} of them: what serving a range reads.
     *
     * @throws FileNotFoundException when no object is stored at the path
     */
    public @NonNull InputStream open(@NonNull String path, long offset, long length) throws IOException {
        return call(() -> this.storage.open(this.keyOf(path), offset, length));
    }

    @Override
    public void writeFile(@NonNull String handle, @NonNull String path, byte @NonNull [] content,
                          @NonNull String mode, @NonNull Map<String, String> ownerLabels) throws IOException {
        call(() -> this.storage.put(this.keyOf(path), new ByteArrayInputStream(content),
            PutOptions.builder().contentLength(content.length).build()));
    }

    @Override
    public void writeFileFrom(@NonNull String handle, @NonNull String path, @NonNull Path source,
                              @NonNull String mode, @NonNull Map<String, String> ownerLabels) throws IOException {
        long size = Files.size(source);
        call(() -> this.storage.put(this.keyOf(path), source, PutOptions.builder().contentLength(size).build()));
    }

    /** Accepted and kept nowhere: a stored object has no permission bits (the class note). */
    @Override
    public void setMode(@NonNull String handle, @NonNull String path, @NonNull String mode,
                        @NonNull Map<String, String> ownerLabels) {
    }

    /** Accepted and kept nowhere: a stored object's time is its store's (the class note). */
    @Override
    public void setModified(@NonNull String handle, @NonNull String path, long epochSeconds,
                            @NonNull Map<String, String> ownerLabels) {
    }

    @Override
    public void makeDirectory(@NonNull String handle, @NonNull String path,
                              @NonNull Map<String, String> ownerLabels) throws IOException {
        call(() -> {
            this.storage.makeFolder(this.keyOf(path));
            return null;
        });
    }

    /** A file moves as one object; a folder moves object by object, its empty folders made anew at the target. */
    @Override
    public void rename(@NonNull String handle, @NonNull String from, @NonNull String to,
                       @NonNull Map<String, String> ownerLabels) throws IOException {
        call(() -> {
            String fromKey = this.keyOf(from);
            String toKey = this.keyOf(to);
            if (this.storage.stat(fromKey) != null) {
                this.storage.move(fromKey, toKey);
                return null;
            }
            this.moveFolder(fromKey, toKey);
            this.storage.deleteFolder(fromKey);
            return null;
        });
    }

    private void moveFolder(@NonNull String fromFolder, @NonNull String toFolder) {
        StorageAdapter.Listing listing = this.storage.children(fromFolder);
        if (listing.objects().isEmpty() && listing.folders().isEmpty()) {
            this.storage.makeFolder(toFolder);
            return;
        }
        for (StoredObject object : listing.objects()) {
            this.storage.move(object.key(), toFolder + object.key().substring(fromFolder.length()));
        }
        for (String name : listing.folders()) {
            this.moveFolder(fromFolder + "/" + name, toFolder + "/" + name);
        }
    }

    @Override
    public void delete(@NonNull String handle, @NonNull String path, boolean recursive,
                       @NonNull Map<String, String> ownerLabels) throws IOException {
        call(() -> {
            String key = this.keyOf(path);
            if (!recursive) {
                this.storage.delete(key);
                return null;
            }
            for (String below : this.storage.list(key + "/")) {
                this.storage.delete(below);
            }
            this.storage.deleteFolder(key);
            return null;
        });
    }

    /** One storage call whose refusals reach the file manager as the I/O failures it words. */
    private interface StorageCall<T> {
        T run();
    }

    private static <T> T call(@NonNull StorageCall<T> call) throws IOException {
        try {
            return call.run();
        } catch (StorageException failed) {
            throw switch (failed.kind()) {
                case NOT_FOUND -> new FileNotFoundException(failed.key());
                case INVALID_KEY -> new InvalidName(failed.key());
                case IO -> new IOException(failed.getMessage(), failed);
            };
        }
    }

    /** A path whose name no stored key may spell (a name starting with a dot, a backslash). */
    public static final class InvalidName extends IOException {
        InvalidName(@NonNull String key) {
            super("No stored file may be named '" + key + "'");
        }
    }
}
