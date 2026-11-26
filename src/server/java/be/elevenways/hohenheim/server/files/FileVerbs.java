package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;
import java.util.Map;

/**
 * THE file-manager verbs that change a tree's files (an app's volumes, a site's stored files): each one performs
 * through {@link InstanceFiles} and records its {@code FILES_*} activity verb on the record the files belong to, so the
 * Files tab, the automation API, the tools and SFTP cannot drift on what an action means or which verb it records.
 *
 * AIDEV-NOTE: a verb records only after the service performed it; a refusal records nothing. Reads are never
 * recorded, in any lane. Who acted, from where and over which transport is the activity row's accountability (the
 * request's, or the SFTP session's with origin {@code sftp}), never an argument here. An SFTP mode or time change is
 * no verb of its own: the file manager records content and tree changes only, as the browser always did.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class FileVerbs {

    private final @NonNull InstanceFiles files;

    public FileVerbs() {
        this(new InstanceFiles());
    }

    public FileVerbs(@NonNull InstanceFiles files) {
        this.files = files;
    }

    /** The Files tab's inline editor saved a file. */
    public void save(@NonNull FileRoot root, @NonNull String path, byte @NonNull [] content) {
        this.files.write(root, path, content);
        record(root, HohenheimActivityAction.FILES_SAVE, path);
    }

    /** A file arrived through the Files tab's upload form. */
    public void upload(@NonNull FileRoot root, @NonNull String path, byte @NonNull [] content) {
        this.files.write(root, path, content);
        record(root, HohenheimActivityAction.FILES_UPLOAD, path);
    }

    /**
     * A file arrived through a spooling transport (SFTP), streamed from {@code source}.
     *
     * @param replace false when only a new file may be created
     */
    public void uploadFrom(@NonNull FileRoot root, @NonNull String path, @NonNull Path source, boolean replace,
                           long maxBytes) {
        this.files.writeFrom(root, path, source, replace, maxBytes);
        record(root, HohenheimActivityAction.FILES_UPLOAD, path);
    }

    /** A file was put into one declared volume over the API, before or after the first deploy. */
    public void putInto(int instanceId, @NonNull String volumeRoot, @NonNull String relativePath,
                        @NonNull Path source, long maxBytes) {
        this.files.putFrom(instanceId, volumeRoot, relativePath, source, maxBytes);
        record(FileRoot.app(instanceId), HohenheimActivityAction.FILES_UPLOAD, volumeRoot + "/" + relativePath);
    }

    /** The automation API's or a tool's write replaced a file. */
    public void write(@NonNull FileRoot root, @NonNull String path, byte @NonNull [] content) {
        this.files.write(root, path, content);
        record(root, HohenheimActivityAction.FILES_WRITE, path);
    }

    public void makeDirectory(@NonNull FileRoot root, @NonNull String path) {
        this.files.makeDirectory(root, path);
        record(root, HohenheimActivityAction.FILES_MKDIR, path);
    }

    /** Records the path the entry was renamed FROM, as every lane always did. */
    public void rename(@NonNull FileRoot root, @NonNull String from, @NonNull String to) {
        this.files.rename(root, from, to);
        record(root, HohenheimActivityAction.FILES_RENAME, from);
    }

    public void delete(@NonNull FileRoot root, @NonNull String path) {
        this.files.delete(root, path);
        record(root, HohenheimActivityAction.FILES_DELETE, path);
    }

    private static void record(@NonNull FileRoot root, @NonNull HohenheimActivityAction verb, @NonNull String path) {
        History.event(root.model(), root.recordId(), Change.fact(verb, Map.of(HohenheimActivityAction.PATH_KEY, path)));
    }
}
