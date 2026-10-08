package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;

/**
 * THE file-manager verbs that change an instance's files: each one performs through {@link InstanceFiles} and records
 * its {@code FILES_*} activity verb, so the Files tab, the automation API and SFTP cannot drift on what an action
 * means or which verb it records.
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
    public void save(int instanceId, @NonNull String path, byte @NonNull [] content) {
        this.files.write(instanceId, path, content);
        record(instanceId, HohenheimActivityAction.FILES_SAVE, path);
    }

    /** A file arrived through the Files tab's upload form. */
    public void upload(int instanceId, @NonNull String path, byte @NonNull [] content) {
        this.files.write(instanceId, path, content);
        record(instanceId, HohenheimActivityAction.FILES_UPLOAD, path);
    }

    /**
     * A file arrived through a spooling transport (SFTP), streamed from {@code source}.
     *
     * @param replace false when only a new file may be created
     */
    public void uploadFrom(int instanceId, @NonNull String path, @NonNull Path source, boolean replace,
                           long maxBytes) {
        this.files.writeFrom(instanceId, path, source, replace, maxBytes);
        record(instanceId, HohenheimActivityAction.FILES_UPLOAD, path);
    }

    /** The automation API's write route replaced a file. */
    public void write(int instanceId, @NonNull String path, byte @NonNull [] content) {
        this.files.write(instanceId, path, content);
        record(instanceId, HohenheimActivityAction.FILES_WRITE, path);
    }

    public void makeDirectory(int instanceId, @NonNull String path) {
        this.files.makeDirectory(instanceId, path);
        record(instanceId, HohenheimActivityAction.FILES_MKDIR, path);
    }

    /** Records the path the entry was renamed FROM, as every lane always did. */
    public void rename(int instanceId, @NonNull String from, @NonNull String to) {
        this.files.rename(instanceId, from, to);
        record(instanceId, HohenheimActivityAction.FILES_RENAME, from);
    }

    public void delete(int instanceId, @NonNull String path) {
        this.files.delete(instanceId, path);
        record(instanceId, HohenheimActivityAction.FILES_DELETE, path);
    }

    private static void record(int instanceId, @NonNull HohenheimActivityAction verb, @NonNull String path) {
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, verb, path);
    }
}
