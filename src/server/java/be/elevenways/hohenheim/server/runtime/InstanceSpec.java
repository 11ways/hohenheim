package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Everything a driver needs to create one instance workload. The owner labels ride the
 * spec because they MUST land at create time -- before any port exists (record-after
 * honesty) and before any named volume is born (Docker never relabels a volume).
 *
 * @param handle        the runtime resource name, e.g. {@code hohenheim-instance-7}
 * @param image         image reference (repo[:tag] resolved by the caller)
 * @param command       command override, or null to keep the image's default
 * @param env           environment variables (name to value, ordered)
 * @param volumes       persistent named volumes: volume name to container path
 * @param publication   the declared port publication, or null for none. Loopback/tcp
 *                      publishes an ephemeral host port recorded AFTER start
 *                      (record-after); UDP, public exposure and fixed host ports ride
 *                      the pre-allocation strategy (see {@link PortPublication}).
 * @param limits        cgroup resource caps
 * @param hardening     the kind's DECLARED capability profile; a required component so a
 *                      new kind cannot inherit isolation by accident (see
 *                      {@link ContainerHardening})
 * @param ownerLabels   the OwnerLabels pair of the owning record
 * @param cloudInitUserData the rendered cloud-init user-data (variables already
 *                      substituted), or null when the kind declares none; a driver that
 *                      cannot deliver it refuses by name, never a silent drop
 * @param imageFingerprint the record's pinned resolved image identity, or null when none
 *                      is recorded yet; a fingerprint-resolving driver recreates an
 *                      ABSENT workload from this pin instead of the mutable alias
 */
public record InstanceSpec(@NonNull String handle,
                           @NonNull String image,
                           @Nullable List<String> command,
                           @NonNull Map<String, String> env,
                           @NonNull Map<String, String> volumes,
                           @Nullable PortPublication publication,
                           @NonNull ResourceLimits limits,
                           ContainerHardening.@NonNull Profile hardening,
                           @NonNull Map<String, String> ownerLabels,
                           @Nullable String cloudInitUserData,
                           @Nullable String imageFingerprint) {

    /** The pre-VM shape: no cloud-init, no pinned fingerprint. */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels) {
        this(handle, image, command, env, volumes, publication, limits, hardening,
            ownerLabels, null, null);
    }

    /** A copy whose publication carries the host port the pre-allocation step claimed. */
    public @NonNull InstanceSpec withPreallocatedPort(int hostPort) {
        if (this.publication == null) {
            throw new IllegalStateException(
                "Spec '" + this.handle + "' declares no publication to pre-allocate for");
        }
        return new InstanceSpec(this.handle, this.image, this.command, this.env, this.volumes,
            this.publication.withPreallocatedPort(hostPort), this.limits, this.hardening,
            this.ownerLabels, this.cloudInitUserData, this.imageFingerprint);
    }

    /** A copy carrying the record's pinned resolved image identity. */
    public @NonNull InstanceSpec withImageFingerprint(@Nullable String fingerprint) {
        return new InstanceSpec(this.handle, this.image, this.command, this.env, this.volumes,
            this.publication, this.limits, this.hardening, this.ownerLabels,
            this.cloudInitUserData, fingerprint);
    }
}
