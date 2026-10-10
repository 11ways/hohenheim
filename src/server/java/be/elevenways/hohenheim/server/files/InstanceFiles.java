package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.hohenheim.server.util.PermissionBits;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final String READ = HohenheimCapabilities.FILES_READ;

    /** Write, upload, rename, delete, mkdir. Elevated: it changes what runs. */
    public static final String WRITE = HohenheimCapabilities.FILES_WRITE;

    /** What {@link #setMode} accepts: permission bits only, never setuid, setgid or sticky. */

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

    /**
     * The instance whose files a caller OUTSIDE the panel (the automation API, SFTP) may
     * reach: live, authored, and its files readable to {@code ctx}.
     *
     * AIDEV-NOTE: absent, trashed, product-tier generated and not permitted are ONE answer,
     * so probing another tenant's id reads exactly like probing a missing one. The
     * {@link InstanceModel#liveAuthored} clause is the scope InstanceApi and
     * TenantScopes.INSTANCES apply: docs/paas-api.md says the automation API never drives a
     * generated instance, and visibility rides files.read because an id whose files the
     * caller may not even list must read as nonexistent.
     *
     * @return the instance row, or null for every refusal alike
     */
    public static @Nullable Row reachableInstance(@NonNull AccessContext ctx, int instanceId) {
        Row row = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.liveAuthored())
            .first();
        return row == null || !HohenheimAccess.hasInstanceCapability(ctx, instanceId, READ) ? null : row;
    }

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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_no_volumes"));
        }
        String path = requestedPath == null || requestedPath.isEmpty()
            ? opened.roots().get(0) : requestedPath;
        InstanceFilePath target = opened.parse(path);
        InstanceFileSupport.Entry leaf = opened.walk(target);
        if (leaf == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_found"));
        }
        if (leaf.kind() != InstanceFileSupport.Kind.DIRECTORY) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_a_directory"));
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
        long cap = maxFileBytes();
        requireReadableFile(opened.walk(target), cap);
        try {
            return opened.files().readFile(opened.handle(), target.absolute(), cap);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /**
     * A read's leaf must be a regular file within the cap.
     *
     * AIDEV-NOTE: a symlink LEAF is refused for reading too: following it is the daemon's
     * resolution, not ours, and it lands wherever the link points.
     */
    private static void requireReadableFile(InstanceFileSupport.@Nullable Entry leaf, long cap) {
        if (leaf == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_found"));
        }
        if (leaf.kind() != InstanceFileSupport.Kind.FILE) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_a_file"));
        }
        if (leaf.size() > cap) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_too_large").withArg("bytes", cap));
        }
    }

    /**
     * What one path is, without following a symlink.
     *
     * @return the entry, or null when nothing is there (an absent ancestor included)
     * @throws Violations {@code files_path_refused} for a path outside the volumes or
     *         through a symlinked or non-directory ancestor
     */
    public @Nullable Entry stat(int instanceId, @NonNull String requestedPath) {
        Opened opened = open(instanceId, READ);
        InstanceFilePath target = opened.parse(requestedPath);
        InstanceFileSupport.Entry leaf = opened.lookup(target);
        if (leaf == null) {
            return null;
        }
        return new Entry(target.name(), target.absolute(), leaf.kind().name(), leaf.size(),
            leaf.modified(), leaf.mode(), managedPaths(instanceId).contains(target.absolute()));
    }

    /**
     * {@link #read} streamed to a host file, for a transport that spools to disk.
     *
     * @param maxBytes the caller's cap, enforced before and DURING the transfer
     * @return the bytes written to {@code out}
     * @throws Violations {@code files_too_large} with nothing usable left in {@code out}
     */
    public long readTo(int instanceId, @NonNull String requestedPath, @NonNull Path out, long maxBytes) {
        Opened opened = open(instanceId, READ);
        InstanceFilePath target = opened.parse(requestedPath);
        requireReadableFile(opened.walk(target), maxBytes);
        try {
            return opened.files().readFileTo(opened.handle(), target.absolute(), out, maxBytes);
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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_too_large").withArg("bytes", cap));
        }
        FileTarget target = fileTarget(instanceId, requestedPath, true);
        try {
            target.opened().files().writeFile(target.opened().handle(), target.path().absolute(), content,
                target.mode(), target.opened().ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /**
     * {@link #write} from a host file, streamed, for a transport that spools to disk.
     *
     * @param replace  false when only a new file may be created: an existing one refuses
     * @param maxBytes the caller's cap on the file
     */
    public void writeFrom(int instanceId, @NonNull String requestedPath, @NonNull Path source,
                          boolean replace, long maxBytes) {
        HohenheimAccess.requireOperationCapability(instanceId, WRITE);
        try {
            if (Files.size(source) > maxBytes) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_too_large").withArg("bytes", maxBytes));
            }
        } catch (IOException e) {
            throw failure(e);
        }
        FileTarget target = fileTarget(instanceId, requestedPath, replace);
        try {
            target.opened().files().writeFileFrom(target.opened().handle(), target.path().absolute(), source,
                target.mode(), target.opened().ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /**
     * Set one file's or directory's permission bits.
     *
     * @param mode permission bits only, as octal ({@code 0644}); setuid, setgid and sticky
     *             refuse, so no transport can mint a setuid binary in a workload
     */
    public void setMode(int instanceId, @NonNull String requestedPath, @NonNull String mode) {
        if (!PermissionBits.TEXT.matcher(mode).matches()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_mode_refused"));
        }
        ExistingTarget target = attributeTarget(instanceId, requestedPath);
        try {
            target.opened().files().setMode(target.opened().handle(), target.path().absolute(), mode,
                target.opened().ownerLabels());
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Set one file's or directory's modification time. */
    public void setModified(int instanceId, @NonNull String requestedPath, long epochSeconds) {
        ExistingTarget target = attributeTarget(instanceId, requestedPath);
        try {
            target.opened().files().setModified(target.opened().handle(), target.path().absolute(),
                epochSeconds, target.opened().ownerLabels());
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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_exists"));
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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_volume_root"));
        }
        if (opened.walk(from) == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_found"));
        }
        if (opened.walk(to) != null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_exists"));
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
        ExistingTarget target = existingTarget(instanceId, requestedPath);
        Opened opened = target.opened();
        try {
            opened.files().delete(opened.handle(), target.path().absolute(),
                target.leaf().kind() == InstanceFileSupport.Kind.DIRECTORY, opened.ownerLabels());
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
            if (!containedParents(files, handle(), target)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
            }
            return statOrNull(files, handle(), target.absolute());
        }

        /**
         * {@link #walk}, except an ABSENT ancestor means nothing is there instead of a
         * refusal: a question about existence answers it, while a symlinked or
         * non-directory ancestor is still the one refusal.
         *
         * @return the leaf, or null when it or an ancestor is not there
         */
        InstanceFileSupport.@Nullable Entry lookup(@NonNull InstanceFilePath target) {
            InstanceFileSupport files = files();
            if (!containedParents(files, handle(), target)) {
                return null;
            }
            return statOrNull(files, handle(), target.absolute());
        }
    }

    /** Where a whole-file write lands: the contained target and the mode it keeps. */
    private record FileTarget(@NonNull Opened opened, @NonNull InstanceFilePath path, @NonNull String mode) {}

    /**
     * The shared preamble of both write lanes, the capability already asked: contained,
     * not managed, and a regular file when something is there (whose mode it keeps).
     *
     * @param replace false when an existing file refuses
     */
    private @NonNull FileTarget fileTarget(int instanceId, @NonNull String requestedPath, boolean replace) {
        Opened opened = resolveOpened(instanceId);
        InstanceFilePath target = opened.parse(requestedPath);
        requireNotManaged(instanceId, target);
        InstanceFileSupport.Entry leaf = opened.walk(target);
        if (leaf == null) {
            return new FileTarget(opened, target, "0644");
        }
        if (leaf.kind() != InstanceFileSupport.Kind.FILE) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_a_file"));
        }
        if (!replace) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_exists"));
        }
        return new FileTarget(opened, target, leaf.mode());
    }

    /** An existing entry a write changes: WRITE asked, contained, not managed, never a volume root. */
    private record ExistingTarget(@NonNull Opened opened, @NonNull InstanceFilePath path,
                                  InstanceFileSupport.@NonNull Entry leaf) {}

    private @NonNull ExistingTarget existingTarget(int instanceId, @NonNull String requestedPath) {
        Opened opened = open(instanceId, WRITE);
        InstanceFilePath target = opened.parse(requestedPath);
        requireNotManaged(instanceId, target);
        if (target.isVolumeRoot()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_volume_root"));
        }
        InstanceFileSupport.Entry leaf = opened.walk(target);
        if (leaf == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_not_found"));
        }
        return new ExistingTarget(opened, target, leaf);
    }

    /** {@link #existingTarget}, never a link: the entry whose attributes change. */
    private @NonNull ExistingTarget attributeTarget(int instanceId, @NonNull String requestedPath) {
        ExistingTarget target = existingTarget(instanceId, requestedPath);
        InstanceFileSupport.Kind kind = target.leaf().kind();
        if (kind != InstanceFileSupport.Kind.FILE && kind != InstanceFileSupport.Kind.DIRECTORY) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_link_refused"));
        }
        return target;
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
     * @return false when an ancestor is simply absent, which {@link Opened#walk} refuses
     *         like any other failed walk and {@link Opened#lookup} answers as "not there"
     * @throws Violations {@code files_path_refused} -- the SAME refusal a lexically bad
     *         path gets, so the walk reveals nothing about what is where
     */
    private static boolean containedParents(@NonNull InstanceFileSupport files,
                                            @NonNull String handle,
                                            @NonNull InstanceFilePath target) {
        List<InstanceFilePath> chain = target.chainFromRoot();
        for (int i = 0; i < chain.size() - 1; i++) {
            InstanceFileSupport.Entry entry = statOrNull(files, handle, chain.get(i).absolute());
            if (entry == null) {
                return false;
            }
            if (entry.kind() != InstanceFileSupport.Kind.DIRECTORY) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_path_refused"));
            }
        }
        return true;
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
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_managed_config"));
        }
        String prefix = target.absolute().endsWith("/")
            ? target.absolute() : target.absolute() + "/";
        for (String path : managed) {
            if (path.startsWith(prefix)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_managed_ancestor"));
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
        throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_unsupported"));
    }

    /** UTF-8 text convenience for the editor surfaces. */
    public @NonNull String readText(int instanceId, @NonNull String path) {
        return new String(this.read(instanceId, path), StandardCharsets.UTF_8);
    }

    private static int maxEntries() {
        return HohenheimSettings.positiveOrDefault(HohenheimSettings.Files.MAX_ENTRIES);
    }

    /** The cap on ONE file, in both directions. */
    public static long maxFileBytes() {
        return HohenheimSettings.positiveOrDefault(HohenheimSettings.Files.MAX_FILE_KB) * 1024L;
    }

    /**
     * Map a driver failure onto a named refusal. A workload that cannot be reached by exec
     * right now gets its OWN key, because "your server is stopped" and "something broke"
     * are different things an operator must be able to tell apart.
     */
    private static @NonNull Violations failure(@NonNull IOException error) {
        if (error instanceof DockerInstanceRuntime.WorkloadNotBrowsableException) {
            return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_workload_not_browsable"));
        }
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("files_failed")
            .withArg("reason", HohenheimViolations.reasonOf(error)));
    }
}
