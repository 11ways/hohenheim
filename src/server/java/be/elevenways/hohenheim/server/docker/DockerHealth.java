package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The boot-time local Docker daemon probe behind the docker-requiring roles
 * (stacks, instances): a node whose daemon is unreachable must be LOUD at boot
 * (the old behaviour was a silent per-call null), and a node without any such
 * role must never construct a {@link DockerClient} at all.
 *
 * The gate and the client factory are injected (the NftService shape) so tests
 * exercise both faces without a real daemon or global settings.
 */
public final class DockerHealth {

    public enum Status {
        /** No probe ran in this process (test boots that skip ServerMain). */
        UNPROBED,
        /** No docker-requiring role (stacks, instances) is on: docker is not part of this install. */
        DISABLED,
        REACHABLE,
        UNREACHABLE
    }

    // STACKS or INSTANCES: both roles REQUIRE a daemon by definition, so either alone
    // makes silent absence unacceptable (the recon's "do not repeat the role gating"
    // warning). PROXY and DATABASES only MAY use docker, so they stay out -- probing for
    // them would raise a false daemon-down alarm on every docker-less proxy install.
    private static final DockerHealth BOOT = new DockerHealth(
        () -> HohenheimRoles.anyEnabled(HohenheimRoles.Role.STACKS,
            HohenheimRoles.Role.INSTANCES),
        DockerClient::new);

    private final @NonNull BooleanSupplier dockerRoleEnabled;
    private final @NonNull Supplier<DockerClient> clientFactory;
    private volatile @NonNull Status status = Status.UNPROBED;
    private volatile @Nullable String problem;

    /** Test constructor: inject the role gate and the client factory. */
    public DockerHealth(@NonNull BooleanSupplier dockerRoleEnabled,
                        @NonNull Supplier<DockerClient> clientFactory) {
        this.dockerRoleEnabled = dockerRoleEnabled;
        this.clientFactory = clientFactory;
    }

    /** The process-wide instance ServerMain probes and the dashboard reads. */
    public static @NonNull DockerHealth instance() {
        return BOOT;
    }

    /** ServerMain's boot probe over the process-wide instance. */
    public static void probeAtBoot() {
        BOOT.probe();
    }

    /**
     * Probe the local daemon when a docker-requiring role is on; never constructs
     * a client when none is.
     */
    public synchronized @NonNull Status probe() {
        if (!dockerRoleEnabled.getAsBoolean()) {
            status = Status.DISABLED;
            problem = null;
            return status;
        }
        try {
            if (clientFactory.get().ping()) {
                status = Status.REACHABLE;
                problem = null;
                return status;
            }
            recordUnreachable("the daemon did not answer /_ping with OK");
        } catch (Exception e) {
            recordUnreachable(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return status;
    }

    private void recordUnreachable(@NonNull String reason) {
        status = Status.UNREACHABLE;
        problem = reason;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem", reason);
        data.put("message", "roles.stacks is enabled but the local Docker daemon is"
            + " unreachable; stacks cannot deploy or be monitored on this node");
        Blast.slog("hohenheim.docker_unreachable", data);
    }

    public @NonNull Status status() {
        return status;
    }

    /** @return the failure detail, only non-null while {@link #status()} is UNREACHABLE */
    public @Nullable String problem() {
        return problem;
    }
}
