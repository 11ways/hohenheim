package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The file-manager half of the driver seam (the {@link FileStagingSupport} /
 * VolumeSnapshotSupport shape): a runtime that can browse and mutate the files inside a
 * workload implements this beside {@link InstanceRuntime}. A driver that cannot simply
 * does not implement it, and the service refuses with a named violation -- never a
 * silently empty listing.
 *
 * Every path reaching an implementation has ALREADY been through
 * {@code InstanceFilePath} (lexically canonical, inside a declared volume root) and the
 * service's per-component symlink walk. An implementation therefore never re-derives
 * containment, and must never "helpfully" resolve, normalize or follow anything.
 *
 * AIDEV-NOTE: {@link #stat} is LSTAT, always. A stat that follows symlinks would make
 * the service's containment walk a check that cannot fail: it exists precisely to see
 * the link before the daemon resolves through it.
 */
public interface InstanceFileSupport {

    /** What a directory entry is; SYMLINK is never followed by this seam. */
    enum Kind { FILE, DIRECTORY, SYMLINK, OTHER }

    /**
     * One entry as the driver observed it.
     *
     * @param name      the basename only (never a path)
     * @param kind      lstat kind
     * @param size      bytes for a file, the link text length for a symlink
     * @param modified  epoch seconds, or 0 when the driver could not tell
     * @param mode      permission bits as a 4-digit octal string, e.g. {@code 0644}
     */
    record Entry(@NonNull String name, @NonNull Kind kind, long size, long modified,
                 @NonNull String mode) {}

    /**
     * The IMMEDIATE children of a directory, never recursive.
     *
     * @param maxEntries hard cap; an implementation that would exceed it THROWS rather
     *                   than returning a truncated listing that reads like a complete one
     * @throws IOException when the workload cannot be browsed (absent, not running, or
     *                     lacking the tooling the driver needs)
     */
    @NonNull List<Entry> listDirectory(@NonNull String handle, @NonNull String path,
                                       int maxEntries) throws IOException;

    /**
     * lstat one path.
     *
     * @throws java.io.FileNotFoundException when nothing is there
     */
    @NonNull Entry stat(@NonNull String handle, @NonNull String path) throws IOException;

    /**
     * Read a whole file.
     *
     * @param maxBytes cap enforced DURING the transfer; over-size THROWS and returns
     *                 nothing, so a truncated read can never be mistaken for the file
     */
    byte @NonNull [] readFile(@NonNull String handle, @NonNull String path, long maxBytes)
        throws IOException;

    /**
     * Create or replace one file. The workload must carry {@code ownerLabels} -- a
     * same-named FOREIGN container is a loud refusal, never a write.
     */
    void writeFile(@NonNull String handle, @NonNull String path, byte @NonNull [] content,
                   @NonNull String mode, @NonNull Map<String, String> ownerLabels)
        throws IOException;

    /** Create one directory; an existing path is a refusal, never a silent success. */
    void makeDirectory(@NonNull String handle, @NonNull String path,
                       @NonNull Map<String, String> ownerLabels) throws IOException;

    /** Rename within the same volume; an existing destination is a refusal. */
    void rename(@NonNull String handle, @NonNull String from, @NonNull String to)
        throws IOException;

    /** Delete a file, a symlink, or a directory recursively. */
    void delete(@NonNull String handle, @NonNull String path, boolean recursive)
        throws IOException;
}
