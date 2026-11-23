package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.PinnedImages;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A short-lived container on an application's host binding that application's declared volumes and nothing else,
 * through which the file manager's write lane reaches them while no release serves the application.
 *
 * AIDEV-NOTE: this is why a file can be put into a volume before the first deploy without a privileged host write:
 * the bytes enter through the daemon's archive API into a container whose only host paths are the application's own
 * volume directories ({@link InstanceVolumes#mountsFor}, the deploy's own materialization, so the directories and
 * quotas are the ones the first release mounts). A symlink inside a volume resolves inside this container, never on
 * the host, and the containment walk of {@link InstanceFiles} refuses one in the path anyway. The owner labels name
 * the APPLICATION, which is what lets {@link ContainerHardening}'s per-instance bind rule admit exactly its volumes.
 *
 * AIDEV-NOTE: no network ({@code NetworkMode none}), the service profile (the directory-owner chown needs CHOWN),
 * and a sleep that ends the container by itself should the controller die before {@link #close} removes it. A
 * leftover (a removal that failed, a controller that died) is an exited container labelled with the application:
 * the next helper of that application removes its exited ones before it starts, and the periodic instance
 * reconciliation ({@code ReconcileInstanceStatus}) removes every host's ({@link #removeExitedEverywhere}), so they
 * never accumulate. A running helper is never touched: it is a put in progress, or a leftover its sleep ends.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class VolumeHelper implements AutoCloseable {

    /** The daemon states of a container that runs nothing and never will again. */
    private static final Set<String> EXITED = Set.of("exited", "dead");

    /** How long a helper lives at most, should nobody remove it. */
    static final int LIFETIME_SECONDS = 600;

    /** Tells apart the helpers this controller starts for one application. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final @NonNull DockerClient docker;
    private final @NonNull InstanceFileSupport files;
    private final @NonNull String handle;
    private final @NonNull Map<String, String> ownerLabels;
    private final @NonNull List<String> mountedPaths;

    private VolumeHelper(@NonNull DockerClient docker, @NonNull InstanceFileSupport files, @NonNull String handle,
                         @NonNull Map<String, String> ownerLabels, @NonNull List<String> mountedPaths) {
        this.docker = docker;
        this.files = files;
        this.handle = handle;
        this.ownerLabels = ownerLabels;
        this.mountedPaths = mountedPaths;
    }

    /**
     * Create the application's declared volumes on its host, as a deploy does, and start a helper binding them.
     *
     * @throws Violations naming the backend when the host cannot deliver a volume
     * @throws IOException when the daemon refuses the helper; nothing is left running then
     */
    static @NonNull VolumeHelper open(int applicationId) throws IOException {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        String serverName = ServerModel.canonicalNameOf(application == null ? null
            : application.get(InstanceModel.SERVER_ID));
        Map<String, String> mounts = InstanceVolumes.mountsFor(applicationId, serverName);
        DockerClient docker;
        try {
            docker = new ServerService().clientFor(serverName);
        } catch (RuntimeException unaddressable) {
            throw new IOException("the host " + serverName + " has no reachable Docker daemon: "
                + unaddressable.getMessage(), unaddressable);
        }
        String handle = ControllerScope.handle(ControllerScope.KIND_FILES,
            applicationId + "-" + SEQUENCE.incrementAndGet());
        Map<String, String> labels = OwnerLabels.of(InstanceModel.MODEL_ID, applicationId);

        List<Map<String, Object>> binds = new ArrayList<>();
        mounts.forEach((hostPath, containerPath) -> binds.add(Map.of(
            "Type", "bind", "Source", hostPath, "Target", containerPath)));
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", "none");
        hostConfig.put("Mounts", binds);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", PinnedImages.ALPINE);
        spec.put("Labels", labels);
        spec.put("Cmd", List.of("sleep", String.valueOf(LIFETIME_SECONDS)));
        spec.put("HostConfig", hostConfig);

        docker.ensureImage(PinnedImages.ALPINE, null);
        removeExited(docker, applicationId);
        OwnerLabels.removeIfOwnedBy(docker, handle, InstanceModel.MODEL_ID, applicationId);
        docker.createContainer(handle, spec, ContainerHardening.SERVICE);
        VolumeHelper helper = new VolumeHelper(docker,
            new DockerInstanceRuntime(docker, WorkloadNetworkPolicy.forServer(serverName)), handle, labels,
            List.copyOf(mounts.values()));
        try {
            docker.startContainer(handle);
        } catch (IOException | RuntimeException refused) {
            helper.close();
            throw refused;
        }
        return helper;
    }

    @NonNull InstanceFileSupport files() {
        return this.files;
    }

    @NonNull String handle() {
        return this.handle;
    }

    @NonNull Map<String, String> ownerLabels() {
        return this.ownerLabels;
    }

    /** @return the container paths the application's volumes mount at, as the release mounts them */
    @NonNull List<String> mountedPaths() {
        return this.mountedPaths;
    }

    /** Remove the helper; logged, never thrown, so a cleanup failure never hides what the write answered. */
    @Override
    public void close() {
        try {
            this.docker.removeContainer(this.handle, true);
        } catch (IOException | RuntimeException unremoved) {
            Blast.log("FILES: could not remove volume helper", this.handle, "-", unremoved.getMessage());
        }
    }

    /** Removes this controller's exited helpers on every Docker host; a host it cannot ask is logged, never thrown. */
    public static void removeExitedEverywhere() {
        List<String> serverNames;
        ServerService servers = new ServerService();
        try {
            serverNames = servers.dockerNames();
        } catch (RuntimeException unlisted) {
            Blast.log("FILES: could not list the hosts to sweep volume helpers on -", unlisted.getMessage());
            return;
        }
        for (String serverName : serverNames) {
            try {
                removeExited(servers.clientFor(serverName), null);
            } catch (RuntimeException unasked) {
                Blast.log("FILES: could not sweep volume helpers on", serverName, "-", unasked.getMessage());
            }
        }
    }

    /**
     * Removes this controller's exited helpers on one host; logged, never thrown, as a sweep never blocks a write.
     *
     * @param applicationId the application whose helpers go, or null for every application's
     * @return how many were removed
     */
    static int removeExited(@NonNull DockerClient docker, @Nullable Integer applicationId) {
        String prefix = ControllerScope.kindPrefix(ControllerScope.KIND_FILES)
            + (applicationId == null ? "" : applicationId + "-");
        List<Object> containers;
        try {
            containers = docker.listContainers(true);
        } catch (IOException | RuntimeException unlisted) {
            Blast.log("FILES: could not list volume helpers -", unlisted.getMessage());
            return 0;
        }
        int removed = 0;
        for (Object entry : containers) {
            if (!(entry instanceof Map<?, ?> summary) || !EXITED.contains(String.valueOf(summary.get("State")))
                    || !(summary.get("Names") instanceof List<?> names) || names.isEmpty()) {
                continue;
            }
            String name = String.valueOf(names.get(0));
            name = name.startsWith("/") ? name.substring(1) : name;
            OwnerLabels.Owner owner = OwnerLabels.parse(summary.get("Labels") instanceof Map<?, ?> labels
                ? labels : null);
            Integer ownerId = OwnerLabels.instanceIdOf(owner);
            if (!name.startsWith(prefix) || ownerId == null || !OwnerLabels.isOurs(owner)
                    || (applicationId != null && !applicationId.equals(ownerId))) {
                continue;
            }
            try {
                docker.removeContainer(name, false);
                removed++;
            } catch (IOException | RuntimeException unremoved) {
                Blast.log("FILES: could not remove exited volume helper", name, "-", unremoved.getMessage());
            }
        }
        return removed;
    }
}
