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
 * @param publications  the declared port publications, empty for none. Loopback/tcp
 *                      publishes an ephemeral host port recorded AFTER start
 *                      (record-after); UDP, public exposure and fixed host ports ride
 *                      the pre-allocation strategy (see {@link PortPublication}). A
 *                      workload may declare SEVERAL (a web service on 80 and 443 is the
 *                      ordinary case); this is a property of one workload and says
 *                      nothing about any other instance.
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
 * @param imageOrigin   where the image comes from: the public catalog (fetched by alias)
 *                      or a prepared image an operator already published into the
 *                      daemon's own store (never fetched)
 * @param secureBoot    whether the image DECLARES it requires Secure Boot; a catalog
 *                      Linux image never does (unsigned), a prepared image legitimately
 *                      can (e.g. Microsoft-signed media)
 * @param guestAgent    whether the image carries a guest agent capable of exec; false
 *                      makes an exec-driven operation refuse by name instead of waiting
 *                      out the ready timeout and reporting a false timeout
 * @param tmpfs         RAM-backed scratch mounts: container path to size cap in bytes.
 *                      DECLARED discardable storage -- the content dies with the
 *                      workload and never reaches host disk, which is what an ephemeral
 *                      managed database's data directory is. A driver that cannot
 *                      deliver one refuses by name; it must never silently fall back to
 *                      a persistent volume, because "ephemeral" is then a lie that
 *                      leaves tenant data on disk.
 * @param healthCheck   the DECLARED in-workload health probe, or null when the workload
 *                      declares none; a driver that cannot run one refuses BY NAME rather
 *                      than creating a workload whose declared health nobody evaluates
 * @param rootDiskGb    the DECLARED size of the workload's own root disk in GB, or null
 *                      to inherit whatever the image and the daemon's default profile
 *                      give it. A driver that cannot express a per-workload root quota
 *                      refuses BY NAME (the cloud-init shape): accepting a number it
 *                      would ignore is a paper limit, which is worse than the gap,
 *                      because it reports success while enforcing nothing.
 * @param networkLimitMbit the DECLARED bandwidth ceiling of the workload's NICs in
 *                      Mbit/s, or null to leave the wire unshaped. Same contract as
 *                      {@code rootDiskGb} and for the same reason: a driver with no
 *                      per-workload rate limiter refuses BY NAME rather than accepting a
 *                      number it cannot enforce (see {@code NetworkBandwidth}).
 */
public record InstanceSpec(@NonNull String handle,
                           @NonNull String image,
                           @Nullable List<String> command,
                           @NonNull Map<String, String> env,
                           @NonNull Map<String, String> volumes,
                           @NonNull List<PortPublication> publications,
                           @NonNull ResourceLimits limits,
                           ContainerHardening.@NonNull Profile hardening,
                           @NonNull Map<String, String> ownerLabels,
                           @Nullable String cloudInitUserData,
                           @Nullable String imageFingerprint,
                           @NonNull ImageOrigin imageOrigin,
                           boolean secureBoot,
                           boolean guestAgent,
                           @NonNull Map<String, Long> tmpfs,
                           @Nullable HealthCheck healthCheck,
                           @Nullable Integer rootDiskGb,
                           @Nullable Integer networkLimitMbit) {

    /** The pre-VM shape: no cloud-init, no pinned fingerprint, catalog origin, no agent claim. */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels) {
        this(handle, image, command, env, volumes, listOf(publication), limits, hardening,
            ownerLabels, null, null, ImageOrigin.CATALOG, false, true, Map.of(), null, null,
            null);
    }

    /** The pre-tmpfs shape: everything declared, no RAM-backed scratch mount. */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels,
                        @Nullable String cloudInitUserData,
                        @Nullable String imageFingerprint,
                        @NonNull ImageOrigin imageOrigin,
                        boolean secureBoot,
                        boolean guestAgent) {
        this(handle, image, command, env, volumes, listOf(publication), limits, hardening,
            ownerLabels, cloudInitUserData, imageFingerprint, imageOrigin, secureBoot,
            guestAgent, Map.of(), null, null, null);
    }

    /** The pre-root-disk shape: everything declared, root inherited from the image. */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels,
                        @Nullable String cloudInitUserData,
                        @Nullable String imageFingerprint,
                        @NonNull ImageOrigin imageOrigin,
                        boolean secureBoot,
                        boolean guestAgent,
                        @NonNull Map<String, Long> tmpfs) {
        this(handle, image, command, env, volumes, listOf(publication), limits, hardening,
            ownerLabels, cloudInitUserData, imageFingerprint, imageOrigin, secureBoot,
            guestAgent, tmpfs, null, null, null);
    }

    /** The pre-healthcheck shape: everything declared, no runtime-evaluated health gate. */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels,
                        @Nullable String cloudInitUserData,
                        @Nullable String imageFingerprint,
                        @NonNull ImageOrigin imageOrigin,
                        boolean secureBoot,
                        boolean guestAgent,
                        @NonNull Map<String, Long> tmpfs,
                        @Nullable Integer rootDiskGb) {
        this(handle, image, command, env, volumes, listOf(publication), limits, hardening,
            ownerLabels, cloudInitUserData, imageFingerprint, imageOrigin, secureBoot,
            guestAgent, tmpfs, null, rootDiskGb, null);
    }

    /**
     * The widest SINGLE-PUBLICATION shape: everything a kind can declare, including the
     * bandwidth ceiling, with at most one port publication.
     *
     * AIDEV-NOTE: the convenience ladder above it exists so a kind names only what it has
     * an answer for. Watch the ARITY when adding a component: this overload and the
     * canonical constructor differ only in the publication argument, so a call site that
     * passes a {@code List} must pass every component or it silently binds here and fails
     * on the list type (which is exactly what happened to StackServiceKind on the write
     * that introduced networkLimitMbit).
     */
    public InstanceSpec(@NonNull String handle,
                        @NonNull String image,
                        @Nullable List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull Map<String, String> volumes,
                        @Nullable PortPublication publication,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels,
                        @Nullable String cloudInitUserData,
                        @Nullable String imageFingerprint,
                        @NonNull ImageOrigin imageOrigin,
                        boolean secureBoot,
                        boolean guestAgent,
                        @NonNull Map<String, Long> tmpfs,
                        @Nullable Integer rootDiskGb,
                        @Nullable Integer networkLimitMbit) {
        this(handle, image, command, env, volumes, listOf(publication), limits, hardening,
            ownerLabels, cloudInitUserData, imageFingerprint, imageOrigin, secureBoot,
            guestAgent, tmpfs, null, rootDiskGb, networkLimitMbit);
    }

    /**
     * The FIRST declared publication, or null -- the single-publication reading every
     * one-port tier already had. Derived from {@link #publications()}, which stays the
     * one source of truth; there is no second place a publication can be declared.
     */
    public @Nullable PortPublication publication() {
        return this.publications.isEmpty() ? null : this.publications.get(0);
    }

    /**
     * A copy whose publications carry the host ports the pre-allocation step claimed,
     * positionally (the list order is the declaration order and never changes).
     *
     * @throws IllegalStateException when the sizes disagree -- a spec created from a
     *         mismatched claim list would bind ports nobody reserved
     */
    public @NonNull InstanceSpec withPreallocatedPorts(@NonNull List<PortPublication> claimed) {
        if (claimed.size() != this.publications.size()) {
            throw new IllegalStateException("Spec '" + this.handle + "' declares "
                + this.publications.size() + " publications but " + claimed.size()
                + " were claimed");
        }
        return new InstanceSpec(this.handle, this.image, this.command, this.env, this.volumes,
            List.copyOf(claimed), this.limits, this.hardening,
            this.ownerLabels, this.cloudInitUserData, this.imageFingerprint, this.imageOrigin,
            this.secureBoot, this.guestAgent, this.tmpfs, this.healthCheck, this.rootDiskGb,
            this.networkLimitMbit);
    }

    /** A copy carrying the record's pinned resolved image identity. */
    public @NonNull InstanceSpec withImageFingerprint(@Nullable String fingerprint) {
        return new InstanceSpec(this.handle, this.image, this.command, this.env, this.volumes,
            this.publications, this.limits, this.hardening, this.ownerLabels,
            this.cloudInitUserData, fingerprint, this.imageOrigin, this.secureBoot,
            this.guestAgent, this.tmpfs, this.healthCheck, this.rootDiskGb,
            this.networkLimitMbit);
    }

    private static @NonNull List<PortPublication> listOf(@Nullable PortPublication one) {
        return one == null ? List.of() : List.of(one);
    }
}
