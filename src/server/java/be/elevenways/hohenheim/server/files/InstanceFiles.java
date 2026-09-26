package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
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
import java.util.Map;
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
 * The containment argument
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
 * (swap a directory for a symlink between the walk and the operation). It is NOT harmless
 * in general, and an earlier version of this note claimed it was. The archive API and the
 * file scripts act with the DAEMON's (or the container's configured user's) authority,
 * which for most images is container root; a workload whose processes run as a NON-ROOT
 * uid can therefore win the race to make the file manager read or write a path that uid
 * itself could not (/etc/shadow of its own container, a root-owned binary) -- a privilege
 * escalation INSIDE the container. What bounds it is layer 1: the race can never reach the
 * host or another tenant, and every host-side read of what comes back is a strict tar parse
 * that refuses links ({@code util.Tar}). The file scripts narrow it further ({@code chown
 * -h}, {@code mv --}/{@code rm --} on quoted variables). Do not "fix" it by weakening layer 3
 * into a single leaf stat; closing it for real needs the operations to run as the
 * workload's own uid, which the archive API cannot do.
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

    /**
     * Whether this instance's DRIVER can browse files at all.
     *
     * AIDEV-NOTE: a runtime asymmetry, not a failure -- only the Docker driver implements
     * {@code InstanceFileSupport}, so an Incus workload has no file lane yet and every
     * call below refuses it with {@code files_unsupported}. The tab asks this so it can
     * render the absence as a named state instead of an error banner. Asking READ first
     * keeps it from being a capability-free probe of what runs where.
     */
    public boolean isSupported(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, READ);
        return this.instances.resolve(instanceId).runtime() instanceof InstanceFileSupport;
    }

    /** The declared volume roots of an instance, the only places this service will look. */
    public @NonNull List<String> volumeRoots(int instanceId) {
        return open(instanceId, READ).roots();
    }

    /**
     * The immediate children of one directory inside a declared volume.
     *
     * @throws Violations naming the refusal; never a partial or empty-on-failure listing
     */
    public @NonNull Listing list(int instanceId, @Nullable String requestedPath) {
        Opened opened = open(instanceId, READ);
        if (opened.roots().isEmpty()) {
            throw refusal("files_no_volumes");
        }
        String path = requestedPath == null || requestedPath.isEmpty()
            ? opened.roots().get(0) : requestedPath;
        InstanceFilePath target = opened.parse(path);
        InstanceFileSupport.Entry leaf = opened.walk(target);
        if (leaf == null) {
            throw refusal("files_not_found");
        }
        if (leaf.kind() != InstanceFileSupport.Kind.DIRECTORY) {
            throw refusal("files_not_a_directory");
        }

        Set<String> managed = managedPaths(instanceId);
        List<Entry> entries = new ArrayList<>();
        try {
            for (InstanceFileSupport.Entry entry : opened.files().listDirectory(opened.handle(),
                    target.absolute(), maxEntries())) {
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
        return new Listing(target.absolute(), entries, opened.roots());
    }

    /**
     * Read one file whole.
     *
     * @throws Violations {@code files_too_large} when it exceeds the cap -- the transfer
     *         aborts mid-read and NOTHING is returned, so a truncated read can never be
     *         mistaken for the file
     */
    public byte @NonNull [] read(int instanceId, @NonNull String requestedPath) {
        Opened opened = open(instanceId, READ);
        InstanceFilePath target = opened.parse(requestedPath);
        InstanceFileSupport.Entry leaf = opened.walk(target);
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
            return opened.files().readFile(opened.handle(), target.absolute(), cap);
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
        Opened opened = resolveOpened(instanceId);
        InstanceFilePath target = opened.parse(requestedPath);
        requireNotManaged(instanceId, target);
        InstanceFileSupport.Entry leaf = opened.walk(target);
        String mode = "0644";
        if (leaf != null) {
            if (leaf.kind() != InstanceFileSupport.Kind.FILE) {
                throw refusal("files_not_a_file");
            }
            mode = leaf.mode();
        }
        try {
            opened.files().writeFile(opened.handle(), target.absolute(), content, mode,
                opened.ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Create one directory; an existing path is a refusal, never a silent success. */
    public void makeDirectory(int instanceId, @NonNull String requestedPath) {
        Opened opened = open(instanceId, WRITE);
        InstanceFilePath target = opened.parse(requestedPath);
        // A directory where a declared config file is staged would make the next deploy's
        // staging fail (or land INSIDE it): the managed boundary covers mkdir too.
        requireNotManaged(instanceId, target);
        if (opened.walk(target) != null) {
            throw refusal("files_exists");
        }
        try {
            opened.files().makeDirectory(opened.handle(), target.absolute(), opened.ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Rename inside the instance's volumes; both ends are contained independently. */
    public void rename(int instanceId, @NonNull String fromPath, @NonNull String toPath) {
        Opened opened = open(instanceId, WRITE);
        InstanceFilePath from = opened.parse(fromPath);
        InstanceFilePath to = opened.parse(toPath);
        requireNotManaged(instanceId, from);
        requireNotManaged(instanceId, to);
        if (from.isVolumeRoot() || to.isVolumeRoot()) {
            throw refusal("files_volume_root");
        }
        if (opened.walk(from) == null) {
            throw refusal("files_not_found");
        }
        if (opened.walk(to) != null) {
            throw refusal("files_exists");
        }
        try {
            opened.files().rename(opened.handle(), from.absolute(), to.absolute(),
                opened.ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Delete a file, a symlink, or a directory tree; never the volume root itself. */
    public void delete(int instanceId, @NonNull String requestedPath) {
        Opened opened = open(instanceId, WRITE);
        InstanceFilePath target = opened.parse(requestedPath);
        requireNotManaged(instanceId, target);
        if (target.isVolumeRoot()) {
            throw refusal("files_volume_root");
        }
        InstanceFileSupport.Entry leaf = opened.walk(target);
        if (leaf == null) {
            throw refusal("files_not_found");
        }
        try {
            opened.files().delete(opened.handle(), target.absolute(),
                leaf.kind() == InstanceFileSupport.Kind.DIRECTORY, opened.ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    // -- the shared preamble --------------------------------------------------

    /**
     * One verb's resolved instance: the capability already asked, the roots computed. The
     * driver lane and the containment walk are reached through it, so no verb can take the
     * one without the other.
     */
    private record Opened(InstanceService.@NonNull Resolved resolved,
                          @NonNull List<String> roots) {

        @NonNull InstanceFilePath parse(@NonNull String path) {
            return InstanceFilePath.parse(this.roots, path);
        }

        /** @throws Violations {@code files_unsupported} when the driver cannot browse */
        @NonNull InstanceFileSupport files() {
            return filesOf(this.resolved);
        }

        @NonNull String handle() {
            return this.resolved.spec().handle();
        }

        @NonNull Map<String, String> ownerLabels() {
            return this.resolved.spec().ownerLabels();
        }

        /**
         * Layer 3 of the containment argument for {@code target}, then lstat its leaf.
         *
         * @return the leaf, or null when nothing is there
         */
        InstanceFileSupport.@Nullable Entry walk(@NonNull InstanceFilePath target) {
            InstanceFileSupport files = files();
            requireContainedParents(files, handle(), target);
            return statOrNull(files, handle(), target.absolute());
        }
    }

    /** Ask {@code capability} on the SERVICE, then resolve the instance and its browse roots. */
    private @NonNull Opened open(int instanceId, @NonNull String capability) {
        HohenheimAccess.requireOperationCapability(instanceId, capability);
        return resolveOpened(instanceId);
    }

    /** The resolve half of {@link #open}, for a verb that asked its capability itself. */
    private @NonNull Opened resolveOpened(int instanceId) {
        InstanceService.Resolved resolved = this.instances.resolve(instanceId);
        return new Opened(resolved, sortedRoots(resolved));
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

    /**
     * Refuse a mutation that would touch a managed path: the path itself, or a DIRECTORY
     * above one (renaming or deleting {@code /data} takes {@code /data/app.conf} with it).
     *
     * AIDEV-NOTE: the ancestor half is its own key ({@code files_managed_ancestor}) because
     * "that file is managed" would be untrue about a directory. Comparing on a trailing
     * {@code /} keeps {@code /data/app} from claiming {@code /data/app.conf}.
     */
    private static void requireNotManaged(int instanceId, @NonNull InstanceFilePath target) {
        Set<String> managed = managedPaths(instanceId);
        if (managed.contains(target.absolute())) {
            throw refusal("files_managed_config");
        }
        String prefix = target.absolute().endsWith("/")
            ? target.absolute() : target.absolute() + "/";
        for (String path : managed) {
            if (path.startsWith(prefix)) {
                throw refusal("files_managed_ancestor");
            }
        }
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * Deepest root first, so a nested volume claims its own paths before its parent does.
     *
     * AIDEV-NOTE: BOTH mount maps, because a spec has two and they are not alternatives:
     * {@code volumes()} is the driver-managed named volume (docker_container, database,
     * stack service) and {@code binds()} is the Hohenheim-owned host directory under the
     * volume root (workspace, application -- see {@code InstanceVolumes}). Reading only
     * the first left every WORKSPACE with an empty root set, which is the one kind whose
     * whole point is browsing its own files.
     */
    private static @NonNull List<String> sortedRoots(InstanceService.@NonNull Resolved resolved) {
        List<String> mounted = new ArrayList<>(resolved.spec().volumes().values());
        mounted.addAll(resolved.spec().binds().values());
        List<String> roots = new ArrayList<>(InstanceFilePath.rootsOf(mounted));
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
        Integer configured = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Files.MAX_ENTRIES);
        return configured == null || configured <= 0 ? 2000 : configured;
    }

    /** The cap on ONE file, in both directions. */
    public static long maxFileBytes() {
        Integer kilobytes = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Files.MAX_FILE_KB);
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
