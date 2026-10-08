package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.sftp.server.SftpEntry;
import be.elevenways.zenit.sftp.server.SftpMount;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One instance's files as an SFTP tree, for one request: every verb is the file manager's own ({@link InstanceFiles}
 * reads, {@link FileVerbs} changes), so the same {@code files.read}/{@code files.write} checks, path containment and
 * activity verbs apply as on the Files tab.
 *
 * AIDEV-NOTE: an SFTP path IS the container path (/data/world/level.dat), so the activity rows and refusals name the
 * same paths the browser does. The directories ABOVE the volume roots ("/", "/var" for a root at /var/lib/mysql) are
 * virtual: they list the next step towards each root and nothing else, and every change there is refused by
 * {@link InstanceFilePath}'s one refusal, because they lie outside every volume.
 *
 * AIDEV-NOTE: the service's capability refusal ({@code instance_not_permitted}) reaches the client as
 * PERMISSION_DENIED in its own words; every other refusal is a worded failure.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class InstanceSftpMount implements SftpMount {

    /** The modes the virtual directories above the volume roots report. */
    private static final String VIRTUAL_MODE = "0755";

    private final int instanceId;
    private final InstanceFiles files;
    private final FileVerbs verbs;
    private final long maxFileBytes;

    InstanceSftpMount(int instanceId, @NonNull InstanceFiles files, @NonNull FileVerbs verbs, long maxFileBytes) {
        this.instanceId = instanceId;
        this.files = files;
        this.verbs = verbs;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public @NonNull List<SftpEntry> list(@NonNull String path) {
        return refusing(() -> {
            List<String> virtual = this.virtualChildren(path);
            if (virtual != null) {
                List<SftpEntry> entries = new ArrayList<>(virtual.size());
                for (String name : virtual) {
                    entries.add(virtualEntry(name));
                }
                return entries;
            }
            List<SftpEntry> entries = new ArrayList<>();
            for (InstanceFiles.Entry entry : this.files.list(this.instanceId, path).entries()) {
                entries.add(entryOf(entry));
            }
            return entries;
        });
    }

    @Override
    public @Nullable SftpEntry stat(@NonNull String path) {
        return refusing(() -> {
            if (this.virtualChildren(path) != null) {
                return virtualEntry(nameOf(path));
            }
            InstanceFiles.Entry entry = this.files.stat(this.instanceId, path);
            return entry == null ? null : entryOf(entry);
        });
    }

    @Override
    public long readTo(@NonNull String path, @NonNull Path spool, long maxBytes) {
        return refusing(() -> this.files.readTo(this.instanceId, path, spool, maxBytes));
    }

    @Override
    public void writeFrom(@NonNull String path, @NonNull Path spool, boolean replace) {
        refusing(() -> {
            this.verbs.uploadFrom(this.instanceId, path, spool, replace, this.maxFileBytes);
            return null;
        });
    }

    @Override
    public void makeDirectory(@NonNull String path) {
        refusing(() -> {
            this.verbs.makeDirectory(this.instanceId, path);
            return null;
        });
    }

    @Override
    public void rename(@NonNull String from, @NonNull String to) {
        refusing(() -> {
            this.verbs.rename(this.instanceId, from, to);
            return null;
        });
    }

    @Override
    public void delete(@NonNull String path) {
        refusing(() -> {
            this.verbs.delete(this.instanceId, path);
            return null;
        });
    }

    @Override
    public void setMode(@NonNull String path, @NonNull String octal) {
        refusing(() -> {
            this.files.setMode(this.instanceId, path, octal);
            return null;
        });
    }

    @Override
    public void setModified(@NonNull String path, long epochSeconds) {
        refusing(() -> {
            this.files.setModified(this.instanceId, path, epochSeconds);
            return null;
        });
    }

    /**
     * The names one step below a virtual directory towards the volume roots.
     *
     * @return null when {@code path} is no virtual directory (inside a volume, or outside every root's way)
     */
    private @Nullable List<String> virtualChildren(@NonNull String path) {
        List<String> roots = this.files.volumeRoots(this.instanceId);
        String prefix = "/".equals(path) ? "/" : path + "/";
        Set<String> children = new LinkedHashSet<>();
        for (String root : roots) {
            if (root.equals(path) || path.startsWith(root + "/")) {
                return null;
            }
            if (root.startsWith(prefix)) {
                String rest = root.substring(prefix.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        if (children.isEmpty() && !"/".equals(path)) {
            return null;
        }
        return children.stream().sorted().toList();
    }

    private static @NonNull SftpEntry virtualEntry(@NonNull String name) {
        return new SftpEntry(name, SftpEntry.Kind.DIRECTORY, 0, 0, VIRTUAL_MODE);
    }

    private static @NonNull SftpEntry entryOf(InstanceFiles.@NonNull Entry entry) {
        return new SftpEntry(entry.name(), kindOf(InstanceFileSupport.Kind.valueOf(entry.kind())), entry.size(),
            entry.modified(), entry.mode());
    }

    private static SftpEntry.@NonNull Kind kindOf(InstanceFileSupport.@NonNull Kind kind) {
        return switch (kind) {
            case FILE -> SftpEntry.Kind.FILE;
            case DIRECTORY -> SftpEntry.Kind.DIRECTORY;
            case SYMLINK -> SftpEntry.Kind.SYMLINK;
            case OTHER -> SftpEntry.Kind.OTHER;
        };
    }

    /** @return the last segment of an absolute path, empty for the root */
    private static @NonNull String nameOf(@NonNull String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Runs one file-manager call, turning the capability refusal into the permission refusal SFTP reports.
     *
     * AIDEV-NOTE: the violation's key is compared with {@link HohenheimRefusalReason#code()}, which IS the key the
     * service gates throw ({@code OperationGates}), so the mapping reads the refusal's home instead of a literal.
     */
    private static <T> T refusing(@NonNull Supplier<T> call) {
        try {
            return call.get();
        } catch (Violations refused) {
            for (Violation violation : refused) {
                if (HohenheimRefusalReason.INSTANCE_NOT_PERMITTED.code().equals(violation.message().key())) {
                    throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED,
                        "The file manager refused the capability this SFTP request needs", violation.message());
                }
            }
            throw refused;
        }
    }
}
