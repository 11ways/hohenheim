package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * THE per-instance file manager: browse, read, write, upload, download, rename, delete
 * and mkdir inside an instance's OWN declared volumes, and nowhere else.
 *
 * Authorization lives HERE, on the service, behind
 * {@link HohenheimAccess#requireOperationCapability} -- exactly where power, snapshots and
 * backups put theirs. The CMS page, the automation API and any later caller reach this
 * class; a check in a resource would be a second policy, and a second policy is how an API
 * becomes a wider door than the UI it claims to mirror. {@code files.read} and
 * {@code files.write} are asked for SEPARATELY: no read path ever asks for write, and
 * every mutating path asks only for write, so a read-capable tenant is structurally unable
 * to modify anything.
 *
 * <h2>The containment argument</h2>
 *
 * A tenant path reaches a real file through the daemon's archive/exec API, and that route
 * confines NOTHING on its own -- measured against Docker 29.6, not assumed:
 * {@code GET /archive?path=/data/../etc/passwd} serves the container's {@code /etc/passwd},
 * and {@code /data/link/passwd} for a symlink {@code /data/link -> /etc} serves the same
 * file while reporting it as an ORDINARY FILE with no symlink bit anywhere. A containment
 * check that stats only the submitted leaf is therefore a check that cannot catch what it
 * exists for. Three layers, and each one is load-bearing:
 *
 * 1. CROSS-TENANT and HOST containment is structural and needs nothing from us. The
 *    archive API is scoped to ONE container's rootfs -- {@code /../../etc/hostname} clamps
 *    to the container's own file, verified -- and {@code ContainerHardening} refuses host
 *    bind mounts, so the rootfs holds nothing of the host. An instance mounts only its own
 *    named volumes ({@code handle + "-vol-" + name}), so no path, symlinked or not, can
 *    name another tenant's data. This layer holds even if everything below it were wrong.
 * 2. LEXICAL containment ({@link InstanceFilePath}): the submitted path must already BE
 *    the one canonical spelling of a path under a declared volume root. Nothing is
 *    normalized, so no rewrite can turn an escape into an accepted path.
 * 3. RESOLVED containment (this class, {@link #requireContainedParents}): every ancestor
 *    from the volume root down to the leaf's parent is LSTAT-ed and must be a real
 *    DIRECTORY. Since no component is a symlink, the daemon's own path resolution of the
 *    string we send is the identity -- the path we ask for is the path that is touched --
 *    and combined with (2) that path is provably inside the volume root.
 *
 * AIDEV-NOTE: layer 3 has an inherent TOCTOU against code running INSIDE the container
 * (swap a directory for a symlink between the walk and the operation). That is deliberate
 * and it is not a privilege boundary: the only party who can win that race is the tenant
 * themselves, who already has unrestricted access to every file in their own container
 * from inside it, and layer 1 means the race cannot reach the host or another tenant. Do
 * not "fix" it by weakening layer 3 into a single leaf stat.
 */
public final class InstanceFiles {

    /** Read a file, list a directory, download. An ordinary tenant capability. */
    public static final String READ = HohenheimAccess.FILES_READ;

    /** Write, upload, rename, delete, mkdir. Elevated: it changes what runs. */
    public static final String WRITE = HohenheimAccess.FILES_WRITE;

    private final @NonNull InstanceService instances;

    public InstanceFiles() {
        this(new InstanceService());
    }

    public InstanceFiles(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /** One entry of a listing, as the surfaces render it. */
    public record Listing(@NonNull String path, @NonNull List<Entry> entries,
                          @NonNull List<String> volumeRoots) {}

    /**
     * @param managed whether a declared config-file row owns this path, which makes it
     *                read-only here (deploy re-stages it, so a hand edit would silently
     *                revert)
     */
    public record Entry(@NonNull String name, @NonNull String path, @NonNull String kind,
                        long size, long modified, @NonNull String mode, boolean managed) {}

    // -- read lane ------------------------------------------------------------

    /** The declared volume roots of an instance, the only places this service will look. */
    public @NonNull List<String> volumeRoots(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, READ);
        return sortedRoots(this.instances.resolve(instanceId));
    }

    /**
     * The immediate children of one directory inside a declared volume.
     *
     * @throws Violations naming the refusal; never a partial or empty-on-failure listing
     */
    public @NonNull Listing list(int instanceId, @Nullable String requestedPath) {
        HohenheimAccess.requireOperationCapability(instanceId, READ);
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        if (roots.isEmpty()) {
            throw refusal("files_no_volumes");
        }
        String path = requestedPath == null || requestedPath.isEmpty() ? roots.get(0) : requestedPath;
        InstanceFilePath target = InstanceFilePath.parse(roots, path);
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, target);
        InstanceFileSupport.Entry leaf = statOrNull(files, handle, target.absolute());
        if (leaf == null) {
            throw refusal("files_not_found");
        }
        if (leaf.kind() != InstanceFileSupport.Kind.DIRECTORY) {
            throw refusal("files_not_a_directory");
        }

        Set<String> managed = managedPaths(instanceId);
        List<Entry> entries = new ArrayList<>();
        try {
            for (InstanceFileSupport.Entry entry : files.listDirectory(handle, target.absolute(),
                    maxEntries())) {
                String childPath = target.absolute().equals("/")
                    ? "/" + entry.name() : target.absolute() + "/" + entry.name();
                entries.add(new Entry(entry.name(), childPath, entry.kind().name(), entry.size(),
                    entry.modified(), entry.mode(), managed.contains(childPath)));
            }
        } catch (IOException e) {
            throw failure(e);
        }
        entries.sort(Comparator
            .comparing((Entry entry) -> "DIRECTORY".equals(entry.kind()) ? 0 : 1)
            .thenComparing(Entry::name));
        return new Listing(target.absolute(), entries, roots);
    }

    /**
     * Read one file whole.
     *
     * @throws Violations {@code files_too_large} when it exceeds the cap -- the transfer
     *         aborts mid-read and NOTHING is returned, so a truncated read can never be
     *         mistaken for the file
     */
    public byte @NonNull [] read(int instanceId, @NonNull String requestedPath) {
        HohenheimAccess.requireOperationCapability(instanceId, READ);
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        InstanceFilePath target = InstanceFilePath.parse(roots, requestedPath);
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, target);
        InstanceFileSupport.Entry leaf = statOrNull(files, handle, target.absolute());
        if (leaf == null) {
            throw refusal("files_not_found");
        }
        // A symlink LEAF is refused for reading too: following it is the daemon's
        // resolution, not ours, and it lands wherever the link points.
        if (leaf.kind() != InstanceFileSupport.Kind.FILE) {
            throw refusal("files_not_a_file");
        }
        long cap = maxFileBytes();
        if (leaf.size() > cap) {
            throw tooLarge(cap);
        }
        try {
            return files.readFile(handle, target.absolute(), cap);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    // -- write lane -----------------------------------------------------------

    /** Create or replace one file. */
    public void write(int instanceId, @NonNull String requestedPath, byte @NonNull [] content) {
        HohenheimAccess.requireOperationCapability(instanceId, WRITE);
        long cap = maxFileBytes();
        if (content.length > cap) {
            throw tooLarge(cap);
        }
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        InstanceFilePath target = InstanceFilePath.parse(roots, requestedPath);
        requireNotManaged(instanceId, target);
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, target);
        InstanceFileSupport.Entry leaf = statOrNull(files, handle, target.absolute());
        String mode = "0644";
        if (leaf != null) {
            if (leaf.kind() != InstanceFileSupport.Kind.FILE) {
                throw refusal("files_not_a_file");
            }
            mode = leaf.mode();
        }
        try {
            files.writeFile(handle, target.absolute(), content, mode, resolved.spec().ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Create one directory; an existing path is a refusal, never a silent success. */
    public void makeDirectory(int instanceId, @NonNull String requestedPath) {
        HohenheimAccess.requireOperationCapability(instanceId, WRITE);
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        InstanceFilePath target = InstanceFilePath.parse(roots, requestedPath);
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, target);
        if (statOrNull(files, handle, target.absolute()) != null) {
            throw refusal("files_exists");
        }
        try {
            files.makeDirectory(handle, target.absolute(), resolved.spec().ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Rename inside the instance's volumes; both ends are contained independently. */
    public void rename(int instanceId, @NonNull String fromPath, @NonNull String toPath) {
        HohenheimAccess.requireOperationCapability(instanceId, WRITE);
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        InstanceFilePath from = InstanceFilePath.parse(roots, fromPath);
        InstanceFilePath to = InstanceFilePath.parse(roots, toPath);
        requireNotManaged(instanceId, from);
        requireNotManaged(instanceId, to);
        if (from.isVolumeRoot() || to.isVolumeRoot()) {
            throw refusal("files_volume_root");
        }
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, from);
        requireContainedParents(files, handle, to);
        if (statOrNull(files, handle, from.absolute()) == null) {
            throw refusal("files_not_found");
        }
        if (statOrNull(files, handle, to.absolute()) != null) {
            throw refusal("files_exists");
        }
        try {
            files.rename(handle, from.absolute(), to.absolute());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Delete a file, a symlink, or a directory tree; never the volume root itself. */
    public void delete(int instanceId, @NonNull String requestedPath) {
        HohenheimAccess.requireOperationCapability(instanceId, WRITE);
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        List<String> roots = sortedRoots(resolved);
        InstanceFilePath target = InstanceFilePath.parse(roots, requestedPath);
        requireNotManaged(instanceId, target);
        if (target.isVolumeRoot()) {
            throw refusal("files_volume_root");
        }
        InstanceFileSupport files = filesOf(resolved);
        String handle = resolved.spec().handle();

        requireContainedParents(files, handle, target);
        InstanceFileSupport.Entry leaf = statOrNull(files, handle, target.absolute());
        if (leaf == null) {
            throw refusal("files_not_found");
        }
        try {
            files.delete(handle, target.absolute(),
                leaf.kind() == InstanceFileSupport.Kind.DIRECTORY);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    // -- containment ----------------------------------------------------------

    /**
     * Layer 3 of the containment argument: LSTAT every ancestor from the volume root down
     * to the leaf's parent and require a real DIRECTORY. One symlink anywhere in that
     * chain and the daemon would resolve our string to a path we never named.
     *
     * @throws Violations {@code files_path_refused} -- the SAME refusal a lexically bad
     *         path gets, so the walk reveals nothing about what is where
     */
    private static void requireContainedParents(@NonNull InstanceFileSupport files,
                                                @NonNull String handle,
                                                @NonNull InstanceFilePath target) {
        List<InstanceFilePath> chain = target.chainFromRoot();
        for (int i = 0; i < chain.size() - 1; i++) {
            InstanceFileSupport.Entry entry = statOrNull(files, handle, chain.get(i).absolute());
            if (entry == null || entry.kind() != InstanceFileSupport.Kind.DIRECTORY) {
                throw InstanceFilePath.refused();
            }
        }
    }

    /** @return the lstat, or null when the path is simply not there */
    private static InstanceFileSupport.Entry statOrNull(@NonNull InstanceFileSupport files,
                                                        @NonNull String handle,
                                                        @NonNull String path) {
        try {
            return files.stat(handle, path);
        } catch (FileNotFoundException absent) {
            return null;
        } catch (IOException e) {
            throw failure(e);
        }
    }

    // -- the managed-config-file boundary -------------------------------------

    /**
     * Container paths a declared {@link InstanceFileModel} row owns. Deploy re-stages every
     * one of them from the database, so a hand edit here would be silently reverted at the
     * next start -- the exact "a step does less than it claims" shape. They are surfaced
     * read-only and edited through the Provisioning tab instead.
     */
    private static @NonNull Set<String> managedPaths(int instanceId) {
        Set<String> paths = new LinkedHashSet<>();
        for (Row file : Models.get(InstanceFileModel.class).findByInstanceId(instanceId)) {
            String path = file.get(InstanceFileModel.CONTAINER_PATH);
            if (path != null && !path.isEmpty()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static void requireNotManaged(int instanceId, @NonNull InstanceFilePath target) {
        if (managedPaths(instanceId).contains(target.absolute())) {
            throw refusal("files_managed_config");
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** Deepest root first, so a nested volume claims its own paths before its parent does. */
    private static @NonNull List<String> sortedRoots(InstanceService.@NonNull Resolved resolved) {
        List<String> roots = new ArrayList<>(
            InstanceFilePath.rootsOf(resolved.spec().volumes().values()));
        roots.sort(Comparator.comparingInt(String::length).reversed().thenComparing(root -> root));
        return roots;
    }

    /** @throws Violations {@code files_unsupported} when the driver cannot browse at all */
    private static @NonNull InstanceFileSupport filesOf(InstanceService.@NonNull Resolved resolved) {
        if (resolved.runtime() instanceof InstanceFileSupport files) {
            return files;
        }
        throw refusal("files_unsupported");
    }

    /** UTF-8 text convenience for the editor surfaces. */
    public @NonNull String readText(int instanceId, @NonNull String path) {
        return new String(this.read(instanceId, path), StandardCharsets.UTF_8);
    }

    private static int maxEntries() {
        Integer configured = HohenheimSettings.VALUES.getValue(HohenheimSettings.Files.MAX_ENTRIES);
        return configured == null || configured <= 0 ? 2000 : configured;
    }

    /** The cap on ONE file, in both directions. */
    public static long maxFileBytes() {
        Integer kilobytes = HohenheimSettings.VALUES.getValue(HohenheimSettings.Files.MAX_FILE_KB);
        return (kilobytes == null || kilobytes <= 0 ? 8192L : kilobytes.longValue()) * 1024;
    }

    private static @NonNull Violations tooLarge(long cap) {
        return Violations.ofForm(violationText("files_too_large").withArg("bytes", cap));
    }

    /**
     * Map a driver failure onto a named refusal. A workload that cannot be reached by exec
     * right now gets its OWN key, because "your server is stopped" and "something broke"
     * are different things an operator must be able to tell apart.
     */
    private static @NonNull Violations failure(@NonNull IOException error) {
        if (error instanceof DockerInstanceRuntime.WorkloadNotBrowsableException) {
            return refusal("files_workload_not_browsable");
        }
        return Violations.ofForm(violationText("files_failed")
            .withArg("reason", error.getMessage() != null ? error.getMessage() : error.toString()));
    }

    private static @NonNull Violations refusal(@NonNull String key) {
        return Violations.ofForm(violationText(key));
    }

    private static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    /** The instance's display name, for page titles. */
    public static @NonNull String nameOf(@NonNull Row instance) {
        return String.valueOf((Object) instance.get(InstanceModel.NAME));
    }
}
