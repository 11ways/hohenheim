package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;

import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The Docker driver of the instance tier: WRAPS {@link DockerClient} (it is not the
 * seam -- Incus is a different transport shape entirely). One container per instance on
 * its OWN private network, owner labels stamped at create on the container, the network
 * AND every named volume, and at most one declared port publication: loopback/tcp rides
 * an ephemeral host port read back after start (record-after), everything else
 * materializes the ledger's pre-allocated claim (see {@link PortPublication}).
 *
 * AIDEV-NOTE: a PUBLIC bind does not weaken the tenant network policy. The
 * WorkloadNetworkPolicy denies are saddr-scoped to the workload's own subnet, inbound
 * player traffic from outside falls through to the chain's accept policy, and a
 * cross-tenant packet aimed at a public port is DNAT'ed (prerouting, before the forward
 * hook) onto the target's PRIVATE subnet address -- where the existing RFC1918 denies
 * drop it. Loopback stays the default and the deliberate posture for everything that
 * does not declare otherwise: the reverse proxy reaches those ports over 127.0.0.1 and
 * the world cannot.
 *
 * AIDEV-NOTE: every PRIVATE-posture workload REFUSES to deploy on a host that cannot
 * enforce the network policy (see {@link WorkloadNetworkPolicy}). That refusal is the
 * whole point: a workload that starts unprotected can reach the host, the cloud
 * metadata service and every other container on the daemon. Since the isolation wave
 * that covers instances, Docker sites, managed databases and stacks alike (stacks
 * through StackDeployer's own network, not this runtime); the only remaining
 * SHARED_BRIDGE authors are record-less test/preview callers.
 */
public final class DockerInstanceRuntime
        implements InstanceRuntime, VolumeSnapshotSupport, FileStagingSupport, InstallSupport,
                   ConsoleStreamSupport, LinkNetworkSupport, InstanceFileSupport,
                   StatsStreamSupport {

    private final @NonNull DockerClient docker;
    private final @NonNull WorkloadNetworkPolicy policy;
    private final @NonNull NetworkPosture posture;
    private final @NonNull Egress egress;

    public DockerInstanceRuntime(@NonNull DockerClient docker,
                                 @NonNull WorkloadNetworkPolicy policy) {
        this(docker, policy, NetworkPosture.PRIVATE, Egress.OPEN);
    }

    /**
     * @param posture the KIND-declared posture; {@link NetworkPosture#SHARED_BRIDGE} skips
     *        the per-workload network and nft verification entirely, it never degrades a
     *        failed PRIVATE materialization
     * @param egress  the KIND-declared egress posture, materialized into the network
     *        policy alongside the tenant-range denies
     */
    public DockerInstanceRuntime(@NonNull DockerClient docker,
                                 @NonNull WorkloadNetworkPolicy policy,
                                 @NonNull NetworkPosture posture,
                                 @NonNull Egress egress) {
        this.docker = docker;
        this.policy = policy;
        this.posture = posture;
        this.egress = egress;
    }

    /** The KIND-declared network posture this runtime was built with. */
    public @NonNull NetworkPosture posture() {
        return this.posture;
    }

    /** The KIND-declared egress posture this runtime materializes into the policy. */
    public @NonNull Egress egress() {
        return this.egress;
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        if (spec.cloudInitUserData() != null && !spec.cloudInitUserData().isBlank()) {
            // The honest refusal, never a silent drop: Docker has no cloud-init lane,
            // and a spec carrying provisioning that would not run must not start.
            throw new IOException("The docker driver cannot deliver cloud-init user-data"
                + " declared for '" + spec.handle() + "'; cloud-init provisioning is an"
                + " incus capability");
        }
        // Replace only a leftover container the daemon attributes to THIS record; a
        // same-named foreign container is a loud refusal, never a force-remove.
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        // Network AND verified kernel policy first: an unenforceable host must not reach
        // the point where an image is pulled, let alone a container created. A
        // SHARED_BRIDGE kind declared no such network, so there is nothing to verify.
        String network = this.posture == NetworkPosture.PRIVATE
            ? WorkloadNetworks.ensure(this.docker, this.policy, spec.handle(), spec.ownerLabels(),
                this.egress)
            : null;
        this.docker.ensureImage(spec.image(), null);
        OwnerLabels.removeIfOwnedBy(this.docker, spec.handle(), owner.model(), owner.id());
        this.docker.createContainer(spec.handle(), buildSpec(spec, network), spec.hardening());
        return spec.handle();
    }

    @Override
    public void start(@NonNull String handle) throws IOException {
        // Docker networks survive a host reboot; nftables rules do not. Re-verifying here
        // is what stops a reboot from turning every stopped instance into an unisolated one.
        if (this.posture == NetworkPosture.PRIVATE) {
            WorkloadNetworks.ensureForStart(this.docker, this.policy, handle, this.egress);
        }
        this.docker.startContainer(handle);
    }

    @Override
    public void stop(@NonNull String handle, int graceSeconds) throws IOException {
        this.docker.stopContainer(handle, graceSeconds);
    }

    @Override
    public void destroy(@NonNull String handle) throws IOException {
        try {
            this.docker.stopContainer(handle, 10);
        } catch (IOException ignored) {
            // stop is a courtesy; the force-remove below is the authority
        }
        try {
            this.docker.removeContainer(handle, true);
        } catch (DockerClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // refused: NOT gone
            }
            // 404 = observed absent, which is the outcome destroy exists for.
        }
        // The network and its kernel chains are OURS and nothing else uses them, so they
        // die with the workload -- unlike a named volume, there is nothing in them to lose.
        if (this.posture == NetworkPosture.PRIVATE) {
            WorkloadNetworks.teardown(this.docker, this.policy, handle);
        }
    }

    // -- LinkNetworkSupport ---------------------------------------------------

    @Override
    public @NonNull String ensureLinkNetwork(@NonNull String linkHandle,
                                             @NonNull Map<String, String> ownerLabels,
                                             @NonNull Egress egress)
            throws IOException {
        // The SAME create-verify-or-refuse path as a per-instance network: a link network
        // whose kernel policy cannot land never exists to be attached to. The egress is
        // the CALLER'S declaration for the link lane, not this runtime's kind egress.
        return WorkloadNetworks.ensure(this.docker, this.policy, linkHandle, ownerLabels, egress);
    }

    @Override
    public void connectToLinkNetwork(@NonNull String linkHandle, @NonNull String containerHandle)
            throws IOException {
        String network = WorkloadNetworks.networkName(linkHandle);
        for (String member : linkMembers(network)) {
            if (member.equals(containerHandle)) {
                return;
            }
        }
        this.docker.connectContainerToNetwork(network, containerHandle, null);
    }

    @Override
    public void removeLinkNetwork(@NonNull String linkHandle) throws IOException {
        String network = WorkloadNetworks.networkName(linkHandle);
        Map<String, Object> existing = this.docker.findNetworkByName(network);
        if (existing != null) {
            for (String member : linkMembers(network)) {
                this.docker.disconnectContainerFromNetwork(network, member, true);
            }
        }
        WorkloadNetworks.teardown(this.docker, this.policy, linkHandle);
    }

    /** Container NAMES currently attached to a network; empty when the network is absent. */
    private @NonNull List<String> linkMembers(@NonNull String network) throws IOException {
        Map<String, Object> inspect;
        try {
            inspect = this.docker.inspectNetwork(network);
        } catch (DockerClient.ApiException e) {
            if (e.isNotFound()) {
                return List.of();
            }
            throw e;
        }
        List<String> names = new ArrayList<>();
        if (inspect.get("Containers") instanceof Map<?, ?> members) {
            for (Object value : members.values()) {
                if (value instanceof Map<?, ?> member
                        && member.get("Name") instanceof String name && !name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public @NonNull InstanceStatus status(@NonNull String handle) {
        Map<String, Object> inspect;
        try {
            inspect = this.docker.inspectContainer(handle);
        } catch (DockerClient.ApiException e) {
            return new InstanceStatus(e.isNotFound()
                ? ContainerState.ABSENT : ContainerState.UNREACHABLE, null);
        } catch (IOException e) {
            return new InstanceStatus(ContainerState.UNREACHABLE, null);
        }
        Object state = inspect.get("State");
        boolean running = state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running"));
        if (!running) {
            return new InstanceStatus(ContainerState.STOPPED, null);
        }
        Object[] binding = firstPublishedBinding(inspect);
        return new InstanceStatus(ContainerState.RUNNING,
            (Integer) binding[0], (String) binding[1]);
    }

    // -- VolumeSnapshotSupport ------------------------------------------------

    @Override
    public @NonNull List<CapturedVolume> captureVolumes(
            @NonNull InstanceSpec spec, @NonNull Map<String, String> logicalVolumes,
            @NonNull Path directory, long maxBytesPerVolume) throws IOException {
        List<CapturedVolume> captured = new ArrayList<>();
        for (Map.Entry<String, String> volume : logicalVolumes.entrySet()) {
            // Resolving through the SPEC's materialized map (by container path) keeps the
            // volume-naming convention in ONE place -- the kind that materialized it.
            requireMaterialized(spec, volume.getValue());
            Path file = directory.resolve(volume.getKey() + ".tar");
            long size = this.docker.getArchiveTar(spec.handle(), volume.getValue(),
                file, maxBytesPerVolume);
            captured.add(new CapturedVolume(volume.getKey(), volume.getValue(), file, size));
        }
        return captured;
    }

    @Override
    public void restoreVolumes(@NonNull InstanceSpec spec,
                               @NonNull Map<String, String> logicalVolumes,
                               @NonNull Map<String, Path> tars) throws IOException {
        for (Map.Entry<String, Path> tar : tars.entrySet()) {
            String containerPath = logicalVolumes.get(tar.getKey());
            if (containerPath == null) {
                throw new IOException("Backup payload names volume '" + tar.getKey()
                    + "' which this instance's settings do not declare; restore refuses a"
                    + " payload it cannot place");
            }
            requireMaterialized(spec, containerPath);
            // The captured tar is rooted at the directory's basename (Docker's archive
            // envelope), so it extracts at the PARENT of the mount path.
            this.docker.putArchiveTar(spec.handle(), parentOf(containerPath), tar.getValue());
        }
    }

    @Override
    public void removeVolumesForRestore(@NonNull InstanceSpec spec,
                                        @NonNull Map<String, String> logicalVolumes,
                                        @NonNull Collection<String> names) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; a restore cannot verify volume ownership without them");
        }
        for (String name : names) {
            String containerPath = logicalVolumes.get(name);
            if (containerPath == null) {
                continue;
            }
            String materialized = requireMaterialized(spec, containerPath);
            Map<String, Object> inspect;
            try {
                inspect = this.docker.inspectVolume(materialized);
            } catch (DockerClient.ApiException e) {
                if (e.isNotFound()) {
                    continue;   // observed absent: nothing to remove, create will mint it
                }
                throw e;
            }
            OwnerLabels.Owner volumeOwner = inspect.get("Labels") instanceof Map<?, ?> labels
                ? OwnerLabels.parse(labels) : null;
            boolean ours = volumeOwner != null && volumeOwner.model().equals(owner.model())
                && volumeOwner.id().equals(owner.id());
            if (!ours) {
                throw new IOException("REFUSED to remove volume '" + materialized + "' for a"
                    + " restore: the daemon does not attribute it to this record ("
                    + (volumeOwner != null
                        ? "owned by " + volumeOwner.model() + " #" + volumeOwner.id()
                        : "no hohenheim owner labels")
                    + "). A same-named foreign volume is a name collision, not restore debris.");
            }
            this.docker.removeVolume(materialized, false);
        }
    }

    @Override
    public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) throws IOException {
        Map<String, Object> inspect = this.docker.inspectContainer(spec.handle());
        Object config = inspect.get("Config");
        String reference = config instanceof Map<?, ?> c && c.get("Image") instanceof String ref
            ? ref : spec.image();
        String id = inspect.get("Image") instanceof String imageId ? imageId : null;
        return new ImageIdentity(reference, id);
    }

    // -- FileStagingSupport ---------------------------------------------------

    @Override
    public void stageFiles(@NonNull String handle,
                           @NonNull List<FileStagingSupport.StagedFile> files,
                           @NonNull Map<String, String> ownerLabels) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        // The materialize-on-change path pushes into a container that already exists,
        // so the container must be attributably OURS: a same-named foreign container
        // is a name collision, never a push target.
        requireOurs(handle, ownerLabels, "stage config files into");
        // The StackDeployer staging shape: a temp tree pushed through the archive API,
        // so remote daemons work identically and no host bind mount ever exists.
        java.nio.file.Path staging = java.nio.file.Files.createTempDirectory("hohenheim-instance-files");
        try {
            List<String> relativeFiles = new ArrayList<>();
            for (FileStagingSupport.StagedFile file : files) {
                String relative = file.containerPath().startsWith("/")
                    ? file.containerPath().substring(1) : file.containerPath();
                if (relative.isBlank()) {
                    throw new IOException("Refusing config file path '" + file.containerPath() + "'");
                }
                java.nio.file.Path target = staging.resolve(relative).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Refusing config file path '" + file.containerPath() + "'");
                }
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, file.content());
                applyMode(target, file.mode());
                relativeFiles.add(relative);
            }
            // File entries ONLY: a tar that carries the intermediate directories re-owns
            // an EXISTING container directory to root at extraction, which bricks any
            // non-root image whose config lives in its own writable volume (Velocity).
            this.docker.putArchiveFiles(handle, "/", staging, relativeFiles);
        } finally {
            deleteRecursively(staging);
        }
    }

    private static void applyMode(java.nio.file.Path file, String mode) throws IOException {
        try {
            int octal = Integer.parseInt(mode, 8);
            StringBuilder permissions = new StringBuilder(9);
            String symbols = "rwxrwxrwx";
            for (int bit = 8; bit >= 0; bit--) {
                permissions.append((octal & (1 << bit)) != 0 ? symbols.charAt(8 - bit) : '-');
            }
            java.nio.file.Files.setPosixFilePermissions(file,
                java.nio.file.attribute.PosixFilePermissions.fromString(permissions.toString()));
        } catch (NumberFormatException error) {
            throw new IOException("Bad file mode '" + mode + "' (expected octal like 0644)");
        } catch (UnsupportedOperationException unsupported) {
            // Non-POSIX staging filesystem: the tar carries default modes instead.
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    // -- InstanceFileSupport --------------------------------------------------

    /**
     * The ONE shell script the file manager runs, per operation. Every one of them is a
     * CONSTANT: the paths it works on arrive as ENVIRONMENT VARIABLES and are only ever
     * referenced quoted, so no filename can become a command no matter what it contains.
     * A path is never interpolated into these strings.
     *
     * AIDEV-NOTE: list/mkdir/rename/delete go through {@code docker exec} and NOT through
     * the archive API, for two reasons that are both structural. (1) The archive API has
     * no listing, rename or delete verb at all -- a directory GET is a fully RECURSIVE
     * tar, so "list a 20 GiB world directory" would transfer 20 GiB. (2) An exec runs as
     * the container's OWN configured user, so a directory it creates is owned by the
     * workload; a tar extraction lands root-owned, which is exactly how a staged config
     * file once bricked a non-root image. The cost is that these four verbs need a RUNNING
     * workload with a POSIX shell, and that refusal is named and loud (never an empty
     * listing). Read/write/stat deliberately stay on the archive API and work on a
     * STOPPED container.
     */
    private static final String LIST_SCRIPT =
        "find \"$HH_FM_PATH\" -maxdepth 1 -mindepth 1 -exec stat -c '%f %s %Y %n' {} \\;";
    private static final String MKDIR_SCRIPT = "mkdir \"$HH_FM_PATH\"";
    private static final String RENAME_SCRIPT = "mv -- \"$HH_FM_PATH\" \"$HH_FM_TARGET\"";
    private static final String DELETE_SCRIPT = "rm -f -- \"$HH_FM_PATH\"";
    private static final String DELETE_TREE_SCRIPT = "rm -rf -- \"$HH_FM_PATH\"";
    /** uid:gid of a path, used to keep a rewritten file owned by whoever owned its directory. */
    private static final String OWNER_SCRIPT = "stat -c '%u:%g' \"$HH_FM_PATH\"";
    private static final String CHOWN_SCRIPT = "chown \"$HH_FM_OWNER\" \"$HH_FM_PATH\"";

    @Override
    public @NonNull List<Entry> listDirectory(@NonNull String handle, @NonNull String path,
                                              int maxEntries) throws IOException {
        String output = runFileScript(handle, LIST_SCRIPT, Map.of("HH_FM_PATH", path));
        List<Entry> entries = new ArrayList<>();
        String prefix = path.endsWith("/") ? path : path + "/";
        for (String line : output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Entry entry = parseListLine(line, prefix);
            if (entry == null) {
                // A filename carrying a newline splits into lines that do not parse. Skipping
                // is safe because a listed name is never trusted for a later operation --
                // every path is re-parsed and re-walked from scratch -- so the worst case is
                // an unreachable file, never a wrong one.
                continue;
            }
            if (entries.size() >= maxEntries) {
                throw new IOException("Directory '" + path + "' holds more than " + maxEntries
                    + " entries; refusing to return a truncated listing");
            }
            entries.add(entry);
        }
        return entries;
    }

    /** {@code <hex mode> <size> <mtime> <absolute path>}; null when the line is not that. */
    private static Entry parseListLine(@NonNull String line, @NonNull String prefix) {
        int first = line.indexOf(' ');
        int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
        int third = second < 0 ? -1 : line.indexOf(' ', second + 1);
        if (third < 0) {
            return null;
        }
        String name = line.substring(third + 1);
        if (!name.startsWith(prefix) || name.indexOf('/', prefix.length()) >= 0) {
            return null;
        }
        long mode;
        long size;
        long modified;
        try {
            mode = Long.parseLong(line.substring(0, first), 16);
            size = Long.parseLong(line.substring(first + 1, second));
            modified = Long.parseLong(line.substring(second + 1, third));
        } catch (NumberFormatException notAnEntry) {
            return null;
        }
        return new Entry(name.substring(prefix.length()), kindOfStatMode(mode), size, modified,
            octalPermissions(mode));
    }

    /** POSIX {@code st_mode} type nibble, as {@code stat -c %f} reports it. */
    private static @NonNull Kind kindOfStatMode(long mode) {
        return switch ((int) (mode & 0170000)) {
            case 0040000 -> Kind.DIRECTORY;
            case 0120000 -> Kind.SYMLINK;
            case 0100000 -> Kind.FILE;
            default -> Kind.OTHER;
        };
    }

    private static @NonNull String octalPermissions(long mode) {
        StringBuilder octal = new StringBuilder(Long.toOctalString(mode & 0777));
        while (octal.length() < 4) {
            octal.insert(0, '0');
        }
        return octal.toString();
    }

    @Override
    public @NonNull Entry stat(@NonNull String handle, @NonNull String path) throws IOException {
        DockerClient.PathStat stat = this.docker.statArchivePath(handle, path);
        Kind kind = stat.isSymlink() ? Kind.SYMLINK
            : stat.isDirectory() ? Kind.DIRECTORY
            : stat.isRegularFile() ? Kind.FILE : Kind.OTHER;
        int slash = path.lastIndexOf('/');
        return new Entry(slash < 0 ? path : path.substring(slash + 1), kind, stat.size(), 0,
            stat.permissions());
    }

    @Override
    public byte @NonNull [] readFile(@NonNull String handle, @NonNull String path, long maxBytes)
            throws IOException {
        return this.docker.getArchiveFile(handle, path, maxBytes);
    }

    @Override
    public void writeFile(@NonNull String handle, @NonNull String path, byte @NonNull [] content,
                          @NonNull String mode, @NonNull Map<String, String> ownerLabels)
            throws IOException {
        requireOurs(handle, ownerLabels, "write a file into");
        int slash = path.lastIndexOf('/');
        String directory = slash <= 0 ? "/" : path.substring(0, slash);
        String name = path.substring(slash + 1);

        Path staging = Files.createTempDirectory("hohenheim-file-manager");
        try {
            Path target = staging.resolve(name);
            Files.write(target, content);
            applyMode(target, mode);
            // File entries ONLY (never a directory entry): a tar carrying the target
            // directory re-owns it to root at extraction.
            this.docker.putArchiveFiles(handle, directory, staging, List.of(name));
        } finally {
            deleteRecursively(staging);
        }
        // The extraction landed the file root-owned. Hand it back to whoever owns the
        // directory it lives in, so a non-root workload can still write its own file --
        // the same lesson the directory-entry rule above encodes, one level down. A
        // stopped container has no exec, so the ownership fix waits for the next start;
        // that is stated rather than silently skipped.
        try {
            String owner = runFileScript(handle, OWNER_SCRIPT, Map.of("HH_FM_PATH", directory)).trim();
            if (!owner.isEmpty()) {
                runFileScript(handle, CHOWN_SCRIPT, Map.of("HH_FM_PATH", path, "HH_FM_OWNER", owner));
            }
        } catch (WorkloadNotBrowsableException stopped) {
            Blast.log("FILES: wrote", path, "into stopped container", handle,
                "- ownership stays root until the workload runs again");
        }
    }

    @Override
    public void makeDirectory(@NonNull String handle, @NonNull String path,
                              @NonNull Map<String, String> ownerLabels) throws IOException {
        requireOurs(handle, ownerLabels, "create a directory in");
        runFileScript(handle, MKDIR_SCRIPT, Map.of("HH_FM_PATH", path));
    }

    @Override
    public void rename(@NonNull String handle, @NonNull String from, @NonNull String to)
            throws IOException {
        runFileScript(handle, RENAME_SCRIPT, Map.of("HH_FM_PATH", from, "HH_FM_TARGET", to));
    }

    @Override
    public void delete(@NonNull String handle, @NonNull String path, boolean recursive)
            throws IOException {
        runFileScript(handle, recursive ? DELETE_TREE_SCRIPT : DELETE_SCRIPT,
            Map.of("HH_FM_PATH", path));
    }

    /** A workload that cannot be reached by exec right now (stopped, or shell-less). */
    public static final class WorkloadNotBrowsableException extends IOException {
        WorkloadNotBrowsableException(String message) {
            super(message);
        }
    }

    /**
     * Run one of the CONSTANT scripts above with its paths supplied as environment
     * variables, and turn a non-zero exit into a loud failure.
     *
     * @throws WorkloadNotBrowsableException when the container is not running or carries
     *         no {@code /bin/sh} -- a named refusal, never an empty result
     */
    private @NonNull String runFileScript(@NonNull String handle, @NonNull String script,
                                          @NonNull Map<String, String> paths) throws IOException {
        List<String> env = new ArrayList<>();
        paths.forEach((name, value) -> env.add(name + "=" + value));
        DockerClient.ExecResult result;
        try {
            result = this.docker.exec(handle, List.of("/bin/sh", "-c", script), env);
        } catch (DockerClient.ApiException e) {
            // 409 = not running; 404 on the exec create = no such container.
            throw new WorkloadNotBrowsableException("The workload '" + handle
                + "' cannot be browsed right now: " + e.getMessage());
        }
        if (result.exitCode() == 126 || result.exitCode() == 127) {
            throw new WorkloadNotBrowsableException("The workload '" + handle
                + "' carries no POSIX shell or coreutils, so its files cannot be browsed");
        }
        if (result.exitCode() != 0) {
            throw new IOException("File operation failed inside '" + handle + "' (exit "
                + result.exitCode() + "): " + result.stderr().trim());
        }
        return result.stdout();
    }

    /** A same-named FOREIGN container is a name collision, never a write target. */
    private void requireOurs(@NonNull String handle, @NonNull Map<String, String> ownerLabels,
                             @NonNull String what) throws IOException {
        Map<String, Object> inspect = this.docker.inspectContainer(handle);
        Object labels = inspect.get("Config") instanceof Map<?, ?> config
            ? config.get("Labels") : null;
        OwnerLabels.Owner expected = OwnerLabels.parse(ownerLabels);
        OwnerLabels.Owner actual = OwnerLabels.parse(labels instanceof Map<?, ?> map ? map : null);
        if (expected == null || actual == null || !expected.equals(actual)) {
            throw new IOException("REFUSED to " + what + " '" + handle
                + "': the container is not attributably ours (labels: " + labels + ")");
        }
    }

    // -- StatsStreamSupport ---------------------------------------------------

    @Override
    public @NonNull ConsoleStream openStats(@NonNull String handle) throws IOException {
        return this.docker.followStats(handle);
    }

    // -- ConsoleStreamSupport -------------------------------------------------

    @Override
    public @NonNull Console openConsole(@NonNull String handle) throws IOException {
        // stdinDelivered is an OBSERVED fact off the daemon's inspect, because Docker
        // silently discards attach stdin on a container created without OpenStdin --
        // promising delivery there would be the exact silent-success shape this
        // codebase hunts by name.
        Map<String, Object> inspect = this.docker.inspectContainer(handle);
        boolean stdinOpen = inspect.get("Config") instanceof Map<?, ?> config
            && Boolean.TRUE.equals(config.get("OpenStdin"));
        return new Console(this.docker.attach(handle), stdinOpen);
    }

    @Override
    public @NonNull String consoleTail(@NonNull String handle, int lines) throws IOException {
        return this.docker.containerLogs(handle, true, true, lines);
    }

    @Override
    public @Nullable Integer exitCode(@NonNull String handle) throws IOException {
        Map<String, Object> inspect = this.docker.inspectContainer(handle);
        if (!(inspect.get("State") instanceof Map<?, ?> state)) {
            throw new IOException("Container '" + handle + "' inspect carried no State");
        }
        if (Boolean.TRUE.equals(state.get("Running"))) {
            return null;
        }
        return state.get("ExitCode") instanceof Number code ? code.intValue() : -1;
    }

    // -- InstallSupport -------------------------------------------------------

    /** How often the install waiter re-inspects the one-shot workload. */
    private static final long INSTALL_POLL_MS = 500;

    @Override
    public @NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec,
                                              @NonNull String installImage,
                                              @NonNull String script,
                                              @NonNull Map<String, String> env,
                                              long timeoutMs) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable install container is forbidden by design");
        }
        // The install runs INSIDE the instance's private network: it may need egress
        // (downloading server files is the archetypal install step) but must enjoy the
        // same host/metadata/tenant deny policy as the workload itself -- including the
        // kind's declared egress, so a closed-egress kind gets a closed-egress install.
        String network = WorkloadNetworks.ensure(this.docker, this.policy, spec.handle(),
            spec.ownerLabels(), this.egress);
        this.docker.ensureImage(installImage, null);

        String handle = spec.handle() + "-install";
        // Idempotent resume: a leftover install container WE own is replaced; a
        // same-named foreign container stays a refusal inside removeIfOwnedBy.
        OwnerLabels.removeIfOwnedBy(this.docker, handle, owner.model(), owner.id());

        Map<String, Object> containerSpec = new LinkedHashMap<>();
        containerSpec.put("Image", installImage);
        containerSpec.put("Labels", spec.ownerLabels());
        containerSpec.put("Cmd", List.of("/bin/sh", "-c", script));
        containerSpec.put("NetworkingConfig",
            Map.of("EndpointsConfig", Map.of(network, Map.of())));
        List<String> envList = new ArrayList<>();
        env.forEach((name, value) -> envList.add(name + "=" + value));
        if (!envList.isEmpty()) {
            containerSpec.put("Env", envList);
        }
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", network);
        List<Map<String, Object>> mounts = new ArrayList<>();
        spec.volumes().forEach((volumeName, containerPath) -> {
            if (containerPath == null || containerPath.isBlank()) {
                return;
            }
            mounts.add(Map.of(
                "Type", "volume",
                "Source", volumeName,
                "Target", containerPath,
                "VolumeOptions", Map.of("Labels", spec.ownerLabels())));
        });
        if (!mounts.isEmpty()) {
            hostConfig.put("Mounts", mounts);
        }
        containerSpec.put("HostConfig", hostConfig);

        this.docker.createContainer(handle, containerSpec, spec.hardening());
        try {
            this.docker.startContainer(handle);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                Map<String, Object> inspect = this.docker.inspectContainer(handle);
                Object state = inspect.get("State");
                boolean running = state instanceof Map<?, ?> s
                    && Boolean.TRUE.equals(s.get("Running"));
                if (!running) {
                    int exitCode = state instanceof Map<?, ?> s
                        && s.get("ExitCode") instanceof Number code ? code.intValue() : -1;
                    String tail = "";
                    try {
                        tail = this.docker.containerLogs(handle, true, true, 100);
                    } catch (IOException unreadable) {
                        tail = "(install output unreadable: " + unreadable.getMessage() + ")";
                    }
                    return new InstallOutcome(exitCode, tail);
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Install step for '" + spec.handle()
                        + "' exceeded its " + timeoutMs + "ms timeout and was removed");
                }
                try {
                    Thread.sleep(INSTALL_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Install wait interrupted for '" + spec.handle() + "'");
                }
            }
        } finally {
            try {
                this.docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // A leftover install container is re-removed by the next attempt's
                // removeIfOwnedBy; never mask the run's real outcome with cleanup noise.
            }
        }
    }

    /** The materialized volume name behind a container path, resolved off the spec. */
    private static String requireMaterialized(InstanceSpec spec, String containerPath)
            throws IOException {
        for (Map.Entry<String, String> entry : spec.volumes().entrySet()) {
            if (entry.getValue().equals(containerPath)) {
                return entry.getKey();
            }
        }
        throw new IOException("Instance '" + spec.handle() + "' declares no volume mounted at '"
            + containerPath + "'; the snapshot inventory and the instance settings disagree");
    }

    /** The parent directory of a mount path ("/data" -> "/"). */
    private static String parentOf(String containerPath) {
        String normalized = containerPath.endsWith("/") && containerPath.length() > 1
            ? containerPath.substring(0, containerPath.length() - 1) : containerPath;
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "/" : normalized.substring(0, slash);
    }

    /**
     * The one published (host port, bind address) of the inspect payload, or {null, null}
     * (our specs publish at most one port). The bind address is the DAEMON'S report
     * ({@code HostIp}), which is what the exposure verification asserts against.
     */
    private static Object[] firstPublishedBinding(Map<String, Object> inspect) {
        Object ports = inspect.get("NetworkSettings") instanceof Map<?, ?> ns
            ? ns.get("Ports") : null;
        if (!(ports instanceof Map<?, ?> portMap)) {
            return new Object[] {null, null};
        }
        for (Object bindings : portMap.values()) {
            if (bindings instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> binding
                    && binding.get("HostPort") != null) {
                try {
                    return new Object[] {
                        Integer.parseInt(binding.get("HostPort").toString()),
                        binding.get("HostIp") != null
                            ? binding.get("HostIp").toString() : null};
                } catch (NumberFormatException ignored) {
                    return new Object[] {null, null};
                }
            }
        }
        return new Object[] {null, null};
    }

    private static Map<String, Object> buildSpec(InstanceSpec spec, String network) {
        Map<String, Object> containerSpec = new LinkedHashMap<>();
        containerSpec.put("Image", spec.image());
        containerSpec.put("Labels", spec.ownerLabels());
        // Console stdin: game servers take commands on stdin, and attach stdin is
        // silently DISCARDED unless the container was created with OpenStdin. StdinOnce
        // stays false so console reconnects never half-close the workload's stdin.
        containerSpec.put("OpenStdin", true);
        containerSpec.put("StdinOnce", false);
        // AIDEV-NOTE: attached in the CREATE body, never with a connect call afterwards.
        // A post-hoc connect leaves the container on the DEFAULT BRIDGE for the interval in
        // between -- a real window of unisolated tenant runtime, next to every other
        // tenant's container. Post-hoc connect is only acceptable for adding a SECOND
        // network to an already-isolated container. A null network is the SHARED_BRIDGE
        // posture: the default bridge IS the declaration, so nothing is attached here.
        if (network != null) {
            containerSpec.put("NetworkingConfig",
                Map.of("EndpointsConfig", Map.of(network, Map.of())));
        }
        if (spec.command() != null && !spec.command().isEmpty()) {
            containerSpec.put("Cmd", spec.command());
        }
        List<String> env = new ArrayList<>();
        spec.env().forEach((name, value) -> env.add(name + "=" + value));
        if (!env.isEmpty()) {
            containerSpec.put("Env", env);
        }

        Map<String, Object> hostConfig = new LinkedHashMap<>();
        if (network != null) {
            hostConfig.put("NetworkMode", network);
        }
        PortPublication publication = spec.publication();
        if (publication != null) {
            // AIDEV-NOTE: a pre-allocation-shaped publication without a claimed port is a
            // HARD refusal, never a silent fall-back to an ephemeral bind -- an ephemeral
            // public port moves under the DNS rows pointing at it, and an ephemeral UDP
            // port could never be read back (publishedPort is tcp-only). The claim must
            // already exist in the ledger; this driver only materializes it.
            if (publication.requiresPreallocation() && publication.preallocatedPort() == null) {
                throw new IllegalStateException("Publication of '" + spec.handle()
                    + "' (" + publication.protocol()
                    + (publication.publicExposure() ? ", public" : ", loopback")
                    + ") requires a pre-allocated host port and none was claimed;"
                    + " refusing to create the container");
            }
            String portKey = publication.containerPort() + "/" + publication.protocol();
            String hostPort = publication.preallocatedPort() != null
                ? String.valueOf(publication.preallocatedPort()) : "";
            containerSpec.put("ExposedPorts", Map.of(portKey, Map.of()));
            hostConfig.put("PortBindings", Map.of(portKey,
                List.of(Map.of("HostIp", publication.hostBindAddress(),
                    "HostPort", hostPort))));
        }

        // Named volumes carry the owner labels via VolumeOptions at BIRTH -- Docker never
        // relabels an existing volume, so this is the only chance the labels get.
        List<Map<String, Object>> mounts = new ArrayList<>();
        spec.volumes().forEach((volumeName, containerPath) -> {
            if (containerPath == null || containerPath.isBlank()) {
                return;
            }
            mounts.add(Map.of(
                "Type", "volume",
                "Source", volumeName,
                "Target", containerPath,
                "VolumeOptions", Map.of("Labels", spec.ownerLabels())));
        });
        if (!mounts.isEmpty()) {
            hostConfig.put("Mounts", mounts);
        }
        spec.limits().applyTo(hostConfig);
        containerSpec.put("HostConfig", hostConfig);
        return containerSpec;
    }
}
