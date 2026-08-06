package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Executes a {@link StackSpec} against one Docker daemon: policied network, volumes,
 * then services in dependency order with condition gating. Everything it creates carries
 * ownership labels; same-named resources WITHOUT our labels are refused unless the
 * spec opts into adoption, so a deploy can never destroy someone else's container.
 * The per-stack network carries the verified {@link WorkloadNetworkPolicy} kernel deny
 * (metadata range, host, other tenants' RFC1918 space) like every other tier, so a
 * deploy REFUSES on a host that cannot enforce it.
 */
public class StackDeployer {

    /** Ownership label carrying the stack name. */
    public static final String LABEL_STACK = "be.elevenways.hohenheim.stack";

    /** Ownership label carrying the service name. */
    public static final String LABEL_SERVICE = "be.elevenways.hohenheim.stack.service";

    /**
     * The DECLARED isolation profile of every stack service container.
     *
     * AIDEV-NOTE: stacks are OPERATOR-authored (the compose-shaped tier), and their
     * services are the ordinary published-image mix -- nginx, postgres, redis -- all of
     * which chown and drop privileges at entrypoint and all of which refuse to start
     * under STRICT. Tier-level, not per-service, and that is the current honest limit:
     * StackSpec.ServiceSpec has no capability declaration, so a service needing more than
     * SERVICE cannot express it. Adding one is a model field + migration, not an
     * if-chain here, and it must never be reachable by a non-operator author.
     */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    /**
     * The DECLARED egress posture of every stack service.
     *
     * AIDEV-NOTE: OPEN, decided 2026-08-06. A stack is operator-authored compose-shaped
     * content whose services legitimately open outbound connections (package installs at
     * entrypoint, upstream APIs, webhooks); blanket NONE would break those, and the
     * managed-database precedent for NONE (an engine has no legitimate outbound traffic)
     * does not hold here. The tenant-range denies still apply under OPEN -- metadata,
     * host and other tenants stay unreachable. Tier-level like {@link #HARDENING}: a
     * per-stack egress choice would need a model field an OPERATOR flips, never a
     * default that silently widens.
     */
    public static final Egress EGRESS = Egress.OPEN;

    private static final int STOP_GRACE_SECONDS = 10;
    private static final long CONDITION_POLL_MS = 500;
    private static final long STARTED_WAIT_CAP_MS = 30_000;
    private static final long HEALTHY_WAIT_BASE_MS = 120_000;

    private final DockerClient docker;
    private final WorkloadNetworkPolicy policy;
    private final Consumer<String> log;

    /** @param policy the applier bound to the host the {@code docker} client points at
     *                ({@code WorkloadNetworkPolicy.forServer}), never a local default
     *                for a remote daemon */
    public StackDeployer(@NonNull DockerClient docker, @NonNull WorkloadNetworkPolicy policy,
                         @Nullable Consumer<String> log) {
        this.docker = docker;
        this.policy = policy;
        this.log = log != null ? log : line -> {};
    }

    // -- naming ---------------------------------------------------------------

    public static @NonNull String networkName(@NonNull StackSpec spec) {
        return networkName(spec.name());
    }

    /** The per-stack network name from the stack name alone (inventory callers). */
    public static @NonNull String networkName(@NonNull String stackName) {
        return "hohenheim-stack-" + stackName;
    }

    public static @NonNull String containerName(@NonNull StackSpec spec, @NonNull String service) {
        return "hohenheim-stack-" + spec.name() + "-" + service;
    }

    public static @NonNull String volumeName(@NonNull StackSpec spec, StackSpec.@NonNull MountSpec mount) {
        if (mount.externalName() != null) {
            return mount.externalName();
        }
        return "hohenheim-stack-" + spec.name() + "-" + mount.name();
    }

    // -- deploy ---------------------------------------------------------------

    /**
     * Deploy the spec: ensure network and volumes, then per service (dependency
     * order) pull, replace, upload files, start, and gate on dependency conditions.
     *
     * @throws IOException on any Docker failure or refused unowned resource
     */
    public void deploy(@NonNull StackSpec spec) throws IOException {
        log.accept("Deploying stack '" + spec.name() + "' (" + spec.services().size() + " services)");
        ensureNetwork(spec);
        ensureVolumes(spec);

        // Prune BEFORE creating anything: a renamed service's old container still holds
        // its published host ports, so pruning last would fail the new container's start
        // on a port conflict, skip the prune (it sat after the failing loop), and leave
        // every subsequent deploy failing the same way.
        pruneOrphanedContainers(spec);

        DockerClient.RegistryAuth auth = registryAuthOf(spec);

        for (StackSpec.ServiceSpec service : spec.services()) {
            awaitDependencies(spec, service);
            deployService(spec, service, auth);
        }
        log.accept("Stack '" + spec.name() + "' deployed");
    }

    /**
     * Declarative convergence: remove owned containers whose service is no longer in
     * the spec (deleted, disabled or renamed services). Without this they keep running
     * forever -- restart policy unless-stopped -- invisible to status, stop and destroy,
     * which all iterate the CURRENT spec. Only containers carrying THIS stack's
     * ownership label are ever touched.
     */
    private void pruneOrphanedContainers(@NonNull StackSpec spec) throws IOException {
        Set<String> wanted = new HashSet<>();
        for (StackSpec.ServiceSpec service : spec.services()) {
            wanted.add(containerName(spec, service.name()));
        }
        for (Map<String, Object> summary : listOwnedContainerSummaries(spec)) {
            String name = summaryName(summary);
            if (name == null || wanted.contains(name)) {
                continue;
            }
            log.accept("Pruning orphaned container " + name + " (service no longer in the stack)");
            docker.removeContainer(String.valueOf(summary.get("Id")), true);
        }
    }

    /**
     * How many containers (running or not) carry this stack's ownership label.
     *
     * @throws IOException when the daemon cannot answer -- callers gating a
     *         destructive decision must refuse on failure, never assume zero
     */
    public int countOwnedContainers(@NonNull StackSpec spec) throws IOException {
        return listOwnedContainerSummaries(spec).size();
    }

    /** Every container (running or not) carrying this stack's ownership label. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOwnedContainerSummaries(@NonNull StackSpec spec) throws IOException {
        List<Map<String, Object>> owned = new ArrayList<>();
        for (Object entry : docker.listContainers(true)) {
            if (!(entry instanceof Map<?, ?> summary)) {
                continue;
            }
            if (summary.get("Labels") instanceof Map<?, ?> labels
                && spec.name().equals(labels.get(LABEL_STACK))) {
                owned.add((Map<String, Object>) summary);
            }
        }
        return owned;
    }

    /** First name of a /containers/json summary, without the leading slash. */
    private static @Nullable String summaryName(@NonNull Map<String, Object> summary) {
        if (summary.get("Names") instanceof List<?> names && !names.isEmpty()) {
            String name = String.valueOf(names.get(0));
            return name.startsWith("/") ? name.substring(1) : name;
        }
        return null;
    }

    /** Stop every owned container, dependents first (reverse dependency order). */
    public void stop(@NonNull StackSpec spec) throws IOException {
        Set<String> stopped = new HashSet<>();
        List<StackSpec.ServiceSpec> reversed = new ArrayList<>(spec.services());
        java.util.Collections.reverse(reversed);
        for (StackSpec.ServiceSpec service : reversed) {
            String name = containerName(spec, service.name());
            Map<String, Object> existing = findOwnedContainer(spec, name, false);
            if (existing != null) {
                log.accept("Stopping " + name);
                docker.stopContainer(idOf(existing), STOP_GRACE_SECONDS);
                stopped.add(name);
            }
        }
        // A service disabled or removed since the last deploy still has a running
        // container; "stop the stack" must mean the whole stack, not just its spec.
        for (Map<String, Object> summary : listOwnedContainerSummaries(spec)) {
            String name = summaryName(summary);
            if (name != null && stopped.contains(name)) {
                continue;
            }
            log.accept("Stopping " + (name != null ? name : String.valueOf(summary.get("Id"))));
            docker.stopContainer(String.valueOf(summary.get("Id")), STOP_GRACE_SECONDS);
        }
    }

    /**
     * Remove every owned container and the stack network; owned volumes only when
     * {@code removeVolumes} (adopted external volumes are NEVER removed).
     */
    public void destroy(@NonNull StackSpec spec, boolean removeVolumes) throws IOException {
        List<StackSpec.ServiceSpec> reversed = new ArrayList<>(spec.services());
        java.util.Collections.reverse(reversed);
        for (StackSpec.ServiceSpec service : reversed) {
            String name = containerName(spec, service.name());
            Map<String, Object> existing = findOwnedContainer(spec, name, false);
            if (existing != null) {
                log.accept("Removing " + name);
                docker.removeContainer(idOf(existing), true);
            }
        }

        // Sweep by ownership label: containers of services REMOVED from the records
        // are not in the spec's service list but still belong to this stack.
        for (Map<String, Object> summary : listOwnedContainerSummaries(spec)) {
            String name = summaryName(summary);
            log.accept("Removing " + (name != null ? name : String.valueOf(summary.get("Id"))));
            docker.removeContainer(String.valueOf(summary.get("Id")), true);
        }

        // Networks and volumes sweep by ownership label, exactly like containers: a
        // deleted service's named volume is not in the spec's mounts anymore, but it
        // still belongs to this stack. Adopted (external) resources never carry our
        // label and are therefore never removed.
        for (Object entry : docker.listNetworks()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> network = entry instanceof Map<?, ?> ? (Map<String, Object>) entry : null;
            if (network != null && isOwned(labelsOf(network), spec)) {
                String name = String.valueOf(network.get("Name"));
                removePolicyChains(name);
                log.accept("Removing network " + name);
                docker.removeNetwork(name);
            }
        }
        // An ADOPTED network is not owned and stays, but the kernel chains WE applied to
        // it go with the stack that declared them (an already-removed policy is an
        // observed no-op, so the owned case above never double-fails here).
        removePolicyChains(networkName(spec));

        if (removeVolumes) {
            removeOwnedVolumes(spec);
        }
    }

    /**
     * Remove a network's kernel policy chains, unless enforcement is off on this host.
     *
     * AIDEV-NOTE: the decided fate of a stack that was deployed BEFORE enforcement (or
     * on a host whose enforcement was later switched off): deploy REFUSES by name,
     * stop/status keep working, and destroy still tears the stack down -- teardown must
     * never depend on nft being runnable, or such a stack becomes undeletable (the
     * fromRecordsUnordered principle). With enforcement off there is no nft to run;
     * chains applied before a switch-off linger until reboot or re-enable, and they only
     * ever DROP traffic from this stack's now-gone subnet, so lingering is safe.
     */
    private void removePolicyChains(String networkName) throws IOException {
        if (!policy.isEnabled()) {
            log.accept("Skipping kernel policy removal for " + networkName
                + ": enforcement is off on this host, so there is no nft to run");
            return;
        }
        policy.remove(networkName);
    }

    /**
     * Remove every volume carrying this stack's ownership label. IRREVERSIBLE: this is
     * the one Docker resource whose contents cannot be re-fetched, so callers guard it
     * (the admin action requires the operator to type the stack's name). Volumes
     * declared with an external name, and adopted ones, never carry our label and are
     * therefore never removed. A volume attached to ANY container -- stopped ones
     * included -- refuses to go, so callers must remove the containers first
     * (StackRuntime.purgeVolumes destroys the whole stack for exactly that reason).
     */
    public void removeOwnedVolumes(@NonNull StackSpec spec) throws IOException {
        for (Object entry : docker.listVolumes()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> volume = entry instanceof Map<?, ?> ? (Map<String, Object>) entry : null;
            if (volume != null && isOwned(labelsOf(volume), spec)) {
                String name = String.valueOf(volume.get("Name"));
                log.accept("Removing volume " + name);
                docker.removeVolume(name, true);
            }
        }
    }

    /**
     * Live per-service state, best-effort ("missing", "running", "healthy",
     * "unhealthy", "stopped", "starting"). Containers carrying this stack's
     * ownership label whose service is NO LONGER in the spec are reported too,
     * keyed by container name with state "orphaned": a deleted service's
     * still-running container must stay visible to status (and keep the stack
     * from reading as inactive, which would unlock renaming and orphan it for
     * good), not only to the next deploy's prune.
     */
    public @NonNull Map<String, String> status(@NonNull StackSpec spec) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> specNames = new HashSet<>();
        for (StackSpec.ServiceSpec service : spec.services()) {
            String name = containerName(spec, service.name());
            specNames.add(name);
            try {
                Map<String, Object> inspected = docker.inspectContainer(name);
                result.put(service.name(), stateOf(inspected));
            } catch (IOException missing) {
                result.put(service.name(), "missing");
            }
        }
        try {
            for (Map<String, Object> summary : listOwnedContainerSummaries(spec)) {
                String name = summaryName(summary);
                if (name == null || specNames.contains(name)) {
                    continue;
                }
                result.put(name, "orphaned");
            }
        } catch (IOException listFailed) {
            log.accept("Could not sweep for orphaned containers: " + listFailed.getMessage());
        }
        return result;
    }

    // -- pieces ---------------------------------------------------------------

    private void ensureNetwork(StackSpec spec) throws IOException {
        String name = networkName(spec);
        // Loudest, cheapest refusal first: nothing reaches the daemon on a host that
        // cannot enforce the kernel policy (the WorkloadNetworks.ensure ordering).
        policy.requireEnabled(name);

        Map<String, Object> existing = docker.findNetworkByName(name);
        boolean created = false;
        if (existing == null) {
            log.accept("Creating network " + name);
            docker.createNetwork(name, ownershipLabels(spec, null), spec.subnet(), null, false);
            created = true;
        } else if (!isOwned(labelsOf(existing), spec)) {
            if (!spec.adoptResources()) {
                throw new IOException("Network '" + name + "' exists but is not owned by this"
                    + " stack; enable resource adoption to reuse it");
            }
            // AIDEV-NOTE: adoption deliberately does NOT relabel networks or volumes, so
            // they stay un-owned: every later deploy re-logs "Adopting", and destroy leaves
            // them alone. We never remove what we did not create. Containers differ -- they
            // are RECREATED on deploy and so come back carrying our labels.
            log.accept("Adopting existing network " + name);
        }

        // The kernel deny policy is applied and VERIFIED before any service container
        // exists on the network, on EVERY deploy (idempotent; a redeploy after a host
        // reboot restores the chains the reboot dropped). Adopted networks included:
        // adoption reuses a bridge, it must never opt the stack out of the denies.
        try {
            policy.apply(WorkloadNetwork.fromInspect(docker.inspectNetwork(name)), EGRESS);
        } catch (IOException e) {
            if (created) {
                // An unenforced network of ours is debris, not a resource: remove what
                // this call made rather than leaving a usable unprotected network behind.
                try {
                    docker.removeNetwork(name);
                } catch (IOException ignored) {
                    // the throw below is the outcome; a stuck network is the reconciler's
                }
            }
            throw e;
        }
    }

    private void ensureVolumes(StackSpec spec) throws IOException {
        for (StackSpec.ServiceSpec service : spec.services()) {
            for (StackSpec.MountSpec mount : service.mounts()) {
                if (!StackServiceModelSupport.isVolume(mount)) {
                    continue;
                }
                String name = volumeName(spec, mount);
                Map<String, Object> existing = null;
                try {
                    existing = docker.inspectVolume(name);
                } catch (IOException missing) {
                    // Not there yet.
                }
                if (existing != null) {
                    if (isOwned(labelsOf(existing), spec) || mount.externalName() != null) {
                        continue;   // ours, or explicitly adopted by exact name
                    }
                    if (spec.adoptResources()) {
                        log.accept("Adopting existing volume " + name);
                        continue;
                    }
                    throw new IOException("Volume '" + name + "' exists but is not owned by this stack;"
                        + " enable resource adoption (or set an external name) to reuse it");
                }
                if (mount.externalName() != null) {
                    throw new IOException("External volume '" + name + "' does not exist;"
                        + " create it first or drop the external name");
                }
                log.accept("Creating volume " + name);
                docker.createVolume(name, ownershipLabels(spec, service.name()));
            }
        }
    }

    private void deployService(StackSpec spec, StackSpec.ServiceSpec service,
                               DockerClient.RegistryAuth auth) throws IOException {
        String name = containerName(spec, service.name());
        log.accept("Deploying service '" + service.name() + "' (" + service.image() + ")");

        docker.ensureImage(service.image(), null, auth);

        Map<String, Object> existing = findAnyContainer(name);
        if (existing != null) {
            if (!isOwned(labelsOf(configOf(existing)), spec) && !spec.adoptResources()) {
                throw new IOException("Container '" + name + "' exists but is not owned by this stack;"
                    + " enable resource adoption to replace it");
            }
            log.accept("Replacing container " + name);
            docker.removeContainer(idOf(existing), true);
        }

        String id = docker.createContainer(name, containerSpec(spec, service), HARDENING);
        uploadFiles(id, service);
        docker.startContainer(id);
        log.accept("Started " + name);
    }

    private Map<String, Object> containerSpec(StackSpec spec, StackSpec.ServiceSpec service) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("Image", service.image());

        if (!service.command().isEmpty()) {
            container.put("Cmd", service.command());
        }

        if (!service.environment().isEmpty()) {
            List<String> env = new ArrayList<>();
            for (Map.Entry<String, String> variable : service.environment().entrySet()) {
                env.add(variable.getKey() + "=" + variable.getValue());
            }
            container.put("Env", env);
        }

        Map<String, String> labels = ownershipLabels(spec, service.name());
        container.put("Labels", labels);

        if (service.healthCmd() != null) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("Test", List.of("CMD-SHELL", service.healthCmd()));
            health.put("Interval", service.healthIntervalSeconds() * 1_000_000_000L);
            health.put("Timeout", service.healthTimeoutSeconds() * 1_000_000_000L);
            health.put("Retries", service.healthRetries());
            health.put("StartPeriod", service.healthStartPeriodSeconds() * 1_000_000_000L);
            container.put("Healthcheck", health);
        }

        Map<String, Object> exposed = new LinkedHashMap<>();
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (StackSpec.PortSpec port : service.ports()) {
            String key = port.containerPort() + "/" + port.protocol();
            exposed.put(key, Map.of());
            Map<String, Object> binding = new LinkedHashMap<>();
            if (!port.hostIp().isBlank()) {
                binding.put("HostIp", port.hostIp());
            }
            binding.put("HostPort", String.valueOf(port.hostPort()));
            bindings.put(key, List.of(binding));
        }
        if (!exposed.isEmpty()) {
            container.put("ExposedPorts", exposed);
        }

        Map<String, Object> hostConfig = new LinkedHashMap<>();
        if (!bindings.isEmpty()) {
            hostConfig.put("PortBindings", bindings);
        }

        List<Map<String, Object>> mounts = new ArrayList<>();
        for (StackSpec.MountSpec mount : service.mounts()) {
            Map<String, Object> mountMap = new LinkedHashMap<>();
            if (StackServiceModelSupport.isVolume(mount)) {
                mountMap.put("Type", "volume");
                mountMap.put("Source", volumeName(spec, mount));
            } else {
                mountMap.put("Type", "tmpfs");
            }
            mountMap.put("Target", mount.containerPath());
            mounts.add(mountMap);
        }
        if (!mounts.isEmpty()) {
            hostConfig.put("Mounts", mounts);
        }

        hostConfig.put("RestartPolicy", Map.of("Name", service.restartPolicy()));
        hostConfig.put("NetworkMode", networkName(spec));
        ResourceLimits.of(service.memoryLimitMb(), service.cpuLimit()).applyTo(hostConfig);
        container.put("HostConfig", hostConfig);

        // The service name is the container's DNS alias on the stack network.
        container.put("NetworkingConfig", Map.of("EndpointsConfig",
            Map.of(networkName(spec), Map.of("Aliases", List.of(service.name())))));

        return container;
    }

    /**
     * Upload the service's config files into the CREATED (not yet started) container
     * via the archive API -- works against remote daemons, no host bind mounts. The
     * tar carries each file's mode; parent directories are created by extraction.
     */
    private void uploadFiles(String containerId, StackSpec.ServiceSpec service) throws IOException {
        if (service.files().isEmpty()) {
            return;
        }
        Path staging = Files.createTempDirectory("hohenheim-stack-files");
        try {
            for (StackSpec.FileSpec file : service.files()) {
                String relative = file.containerPath().startsWith("/")
                    ? file.containerPath().substring(1)
                    : file.containerPath();
                if (relative.isBlank()) {
                    throw new IOException("Refusing config file path '" + file.containerPath() + "'");
                }
                // Escape check by NORMALIZED containment only: a literal ".." substring test
                // also rejects legitimate names like "/etc/app..conf".
                Path target = staging.resolve(relative).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Refusing config file path '" + file.containerPath() + "'");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content());
                applyMode(target, file.mode());
            }
            docker.putArchiveFromDirectory(containerId, "/", staging);
            log.accept("Uploaded " + service.files().size() + " config file(s)");
        } finally {
            deleteRecursively(staging);
        }
    }

    private void applyMode(Path file, String mode) throws IOException {
        try {
            int octal = Integer.parseInt(mode, 8);
            StringBuilder permissions = new StringBuilder(9);
            String symbols = "rwxrwxrwx";
            for (int bit = 8; bit >= 0; bit--) {
                permissions.append((octal & (1 << bit)) != 0 ? symbols.charAt(8 - bit) : '-');
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions.toString()));
        } catch (NumberFormatException error) {
            throw new IOException("Bad file mode '" + mode + "' (expected octal like 0644)");
        } catch (UnsupportedOperationException unsupported) {
            // Non-POSIX staging filesystem: the tar carries default modes instead. Say so --
            // a 0600 secret silently landing as 0644 inside the container is worth knowing.
            log.accept("Cannot apply mode " + mode + " to " + file.getFileName()
                + " on this filesystem; the container will see default permissions");
        }
    }

    private void awaitDependencies(StackSpec spec, StackSpec.ServiceSpec service) throws IOException {
        for (StackSpec.DependsSpec dependency : service.dependsOn()) {
            String name = containerName(spec, dependency.service());
            boolean needHealthy = "healthy".equals(dependency.condition());
            long startPeriodMs = 0;
            for (StackSpec.ServiceSpec candidate : spec.services()) {
                if (candidate.name().equals(dependency.service())) {
                    startPeriodMs = candidate.healthStartPeriodSeconds() * 1000L;
                    break;
                }
            }
            long deadline = System.currentTimeMillis()
                + (needHealthy ? HEALTHY_WAIT_BASE_MS + startPeriodMs : STARTED_WAIT_CAP_MS);

            log.accept("Waiting for '" + dependency.service() + "' to be "
                + (needHealthy ? "healthy" : "running"));
            while (true) {
                String state = stateOf(docker.inspectContainer(name));
                if (needHealthy ? "healthy".equals(state)
                    : ("running".equals(state) || "healthy".equals(state) || "starting".equals(state))) {
                    break;
                }
                if ("unhealthy".equals(state)) {
                    throw new IOException("Dependency '" + dependency.service() + "' became unhealthy");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for dependency '" + dependency.service()
                        + "' to become " + (needHealthy ? "healthy" : "running") + " (state: " + state + ")");
                }
                try {
                    Thread.sleep(CONDITION_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for dependency '"
                        + dependency.service() + "'");
                }
            }
        }
    }

    // -- helpers --------------------------------------------------------------

    private static DockerClient.RegistryAuth registryAuthOf(StackSpec spec) {
        if (spec.registryUser() == null || spec.registryPassword() == null) {
            return null;
        }
        return new DockerClient.RegistryAuth(spec.registryUser(), spec.registryPassword(), spec.registryServer());
    }

    // AIDEV-NOTE: The stack-NAME-keyed labels above answer "which stack may touch
    // this" (isOwned, prune, destroy, removeOwnedVolumes all key on LABEL_STACK
    // alone); the record-id owner labels answer "which record created this" for the
    // cross-tier reconciler. Both stay: adding the owner pair must never widen what
    // the deployer is willing to remove.
    private Map<String, String> ownershipLabels(StackSpec spec, @Nullable String serviceName) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_STACK, spec.name());
        if (serviceName != null) {
            labels.put(LABEL_SERVICE, serviceName);
        }
        if (spec.stackId() > 0) {
            labels.putAll(OwnerLabels.of(StackModel.MODEL_ID, spec.stackId()));
        }
        return labels;
    }

    private static boolean isOwned(@Nullable Map<?, ?> labels, StackSpec spec) {
        return labels != null && spec.name().equals(labels.get(LABEL_STACK));
    }

    /** Inspect a container by exact name; null when absent. */
    private @Nullable Map<String, Object> findAnyContainer(String name) throws IOException {
        try {
            return docker.inspectContainer(name);
        } catch (IOException missing) {
            return null;
        }
    }

    /** Inspect a container by name, returning it only when owned by this stack. */
    private @Nullable Map<String, Object> findOwnedContainer(StackSpec spec, String name,
                                                             boolean required) throws IOException {
        Map<String, Object> inspected = findAnyContainer(name);
        if (inspected == null) {
            if (required) {
                throw new IOException("Container '" + name + "' does not exist");
            }
            return null;
        }
        if (!isOwned(labelsOf(configOf(inspected)), spec)) {
            if (required) {
                throw new IOException("Container '" + name + "' is not owned by this stack");
            }
            return null;
        }
        return inspected;
    }

    private static String idOf(Map<String, Object> inspected) {
        return String.valueOf(inspected.get("Id"));
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> configOf(Map<String, Object> inspected) {
        return inspected.get("Config") instanceof Map<?, ?> config
            ? (Map<String, Object>) config : null;
    }

    private static @Nullable Map<?, ?> labelsOf(@Nullable Map<String, Object> payload) {
        return payload != null && payload.get("Labels") instanceof Map<?, ?> labels ? labels : null;
    }

    /** Container state token: healthy/unhealthy/starting (with healthcheck), else running/stopped. */
    @SuppressWarnings("unchecked")
    private static String stateOf(Map<String, Object> inspected) {
        Object rawState = inspected.get("State");
        if (!(rawState instanceof Map<?, ?> state)) {
            return "unknown";
        }
        Object health = state.get("Health");
        if (health instanceof Map<?, ?> healthMap && healthMap.get("Status") instanceof String status) {
            return status;   // "starting", "healthy", "unhealthy"
        }
        return Boolean.TRUE.equals(state.get("Running")) ? "running" : "stopped";
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Small shared predicates over spec records. */
    static final class StackServiceModelSupport {
        static boolean isVolume(StackSpec.MountSpec mount) {
            return "volume".equals(mount.type());
        }

        private StackServiceModelSupport() {}
    }
}
