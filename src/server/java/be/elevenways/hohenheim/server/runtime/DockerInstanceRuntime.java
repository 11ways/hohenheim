package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;

import java.io.IOException;
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
 * AND every named volume, an optional loopback-published TCP port read back after start.
 *
 * AIDEV-NOTE: the instance tier is the tenant-authored tier, and it REFUSES to deploy on a
 * host that cannot enforce the network policy (see {@link WorkloadNetworkPolicy}). That
 * refusal is the whole point: an instance that starts unprotected can reach the host, the
 * cloud metadata service and every other container on the daemon. The operator-authored
 * tiers (stacks, Docker sites, managed databases) deliberately do NOT refuse and stay on
 * the shared default bridge -- a declared difference, not an accident.
 */
public final class DockerInstanceRuntime
        implements InstanceRuntime, VolumeSnapshotSupport, FileStagingSupport, InstallSupport {

    /** Published ports bind loopback only: the reverse proxy / operator reaches them, the world does not. */
    private static final String HOST_BIND_ADDRESS = "127.0.0.1";

    private final @NonNull DockerClient docker;
    private final @NonNull WorkloadNetworkPolicy policy;

    public DockerInstanceRuntime(@NonNull DockerClient docker,
                                 @NonNull WorkloadNetworkPolicy policy) {
        this.docker = docker;
        this.policy = policy;
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        // Replace only a leftover container the daemon attributes to THIS record; a
        // same-named foreign container is a loud refusal, never a force-remove.
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        // Network AND verified kernel policy first: an unenforceable host must not reach
        // the point where an image is pulled, let alone a container created.
        String network = InstanceNetworks.ensure(this.docker, this.policy, spec.handle(),
            spec.ownerLabels());
        this.docker.ensureImage(spec.image(), null);
        OwnerLabels.removeIfOwnedBy(this.docker, spec.handle(), owner.model(), owner.id());
        this.docker.createContainer(spec.handle(), buildSpec(spec, network), spec.hardening());
        return spec.handle();
    }

    @Override
    public void start(@NonNull String handle) throws IOException {
        // Docker networks survive a host reboot; nftables rules do not. Re-verifying here
        // is what stops a reboot from turning every stopped instance into an unisolated one.
        InstanceNetworks.ensureForStart(this.docker, this.policy, handle);
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
        InstanceNetworks.teardown(this.docker, this.policy, handle);
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
        return new InstanceStatus(ContainerState.RUNNING, firstPublishedPort(inspect));
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
                           @NonNull List<FileStagingSupport.StagedFile> files) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        // The StackDeployer staging shape: a temp tree pushed through the archive API,
        // so remote daemons work identically and no host bind mount ever exists.
        java.nio.file.Path staging = java.nio.file.Files.createTempDirectory("hohenheim-instance-files");
        try {
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
            }
            this.docker.putArchiveFromDirectory(handle, "/", staging);
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
        // same host/metadata/tenant deny policy as the workload itself.
        String network = InstanceNetworks.ensure(this.docker, this.policy, spec.handle(),
            spec.ownerLabels());
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

    /** The one published host port of the inspect payload, or null (our specs publish at most one). */
    private static @Nullable Integer firstPublishedPort(Map<String, Object> inspect) {
        Object ports = inspect.get("NetworkSettings") instanceof Map<?, ?> ns
            ? ns.get("Ports") : null;
        if (!(ports instanceof Map<?, ?> portMap)) {
            return null;
        }
        for (Object bindings : portMap.values()) {
            if (bindings instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> binding
                    && binding.get("HostPort") != null) {
                try {
                    return Integer.parseInt(binding.get("HostPort").toString());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> buildSpec(InstanceSpec spec, String network) {
        Map<String, Object> containerSpec = new LinkedHashMap<>();
        containerSpec.put("Image", spec.image());
        containerSpec.put("Labels", spec.ownerLabels());
        // AIDEV-NOTE: attached in the CREATE body, never with a connect call afterwards.
        // A post-hoc connect leaves the container on the DEFAULT BRIDGE for the interval in
        // between -- a real window of unisolated tenant runtime, next to every other
        // tenant's container. Post-hoc connect is only acceptable for adding a SECOND
        // network to an already-isolated container.
        containerSpec.put("NetworkingConfig",
            Map.of("EndpointsConfig", Map.of(network, Map.of())));
        if (spec.command() != null && !spec.command().isEmpty()) {
            containerSpec.put("Cmd", spec.command());
        }
        List<String> env = new ArrayList<>();
        spec.env().forEach((name, value) -> env.add(name + "=" + value));
        if (!env.isEmpty()) {
            containerSpec.put("Env", env);
        }

        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", network);
        if (spec.publishPort() != null) {
            String portKey = spec.publishPort() + "/tcp";
            containerSpec.put("ExposedPorts", Map.of(portKey, Map.of()));
            hostConfig.put("PortBindings", Map.of(portKey,
                List.of(Map.of("HostIp", HOST_BIND_ADDRESS, "HostPort", ""))));
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
