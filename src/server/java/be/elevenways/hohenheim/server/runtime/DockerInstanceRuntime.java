package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;

import java.io.IOException;
import java.util.ArrayList;
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
public final class DockerInstanceRuntime implements InstanceRuntime {

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
