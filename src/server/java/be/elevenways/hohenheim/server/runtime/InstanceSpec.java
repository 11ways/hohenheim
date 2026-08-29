package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * @param binds         Hohenheim-OWNED host directories: host path to container path.
 *                      Unlike a named volume, the controller creates, quotas, snapshots
 *                      and removes these itself ({@code InstanceVolumes}), and their
 *                      identity is the OWNER record's, which is what lets a release
 *                      container mount its application's data. Every host path must sit
 *                      under the configured volume root -- {@code ContainerHardening}
 *                      refuses any other bind source, because a bind of an arbitrary host
 *                      path is not an isolation boundary
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
 * @param runUser       the host uid the workload's processes run as, or null to keep
 *                      whatever the image declares. A DECLARED number, never an account:
 *                      there is no unix user on the host behind it, which is what lets a
 *                      workspace own its data directory without a host account existing
 *                      ({@code WorkspaceUids}). A driver that cannot map a uid refuses BY
 *                      NAME -- accepting one it would ignore leaves every file in the
 *                      volume owned by root while the surface claims otherwise.
 * @param networkLimitMbit the DECLARED bandwidth ceiling of the workload's NICs in
 *                      Mbit/s, or null to leave the wire unshaped. Same contract as
 *                      {@code rootDiskGb} and for the same reason: a driver with no
 *                      per-workload rate limiter refuses BY NAME rather than accepting a
 *                      number it cannot enforce (see {@code NetworkBandwidth}).
 * @param tty           whether the primary process runs behind a PSEUDO-TERMINAL
 *                      ({@code ConsoleKind.TTY}): the console echoes, takes keystrokes
 *                      and resize frames, and a full-screen TUI (Alchemy's Janeway)
 *                      renders. False is the plain pipe console. A driver that has no
 *                      pseudo-terminal to give refuses BY NAME rather than attaching a
 *                      pipe and calling it a terminal.
 */
public record InstanceSpec(@NonNull String handle,
                           @NonNull String image,
                           @Nullable List<String> command,
                           @NonNull Map<String, String> env,
                           @NonNull Map<String, String> volumes,
                           @NonNull Map<String, String> binds,
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
                           @Nullable Integer networkLimitMbit,
                           @Nullable Integer runUser,
                           boolean tty) {

    /**
     * Every collection component is defensively copied, so a caller that keeps mutating the
     * map it handed over cannot change a spec a driver already accepted.
     *
     * AIDEV-NOTE: the map copies are order-preserving unmodifiable {@link LinkedHashMap}s
     * rather than {@code Map.copyOf}, because {@code env} is DECLARED ordered (the record
     * docblock) and Map.copyOf randomizes iteration order -- an env list is rendered
     * positionally into the daemon payload.
     */
    public InstanceSpec {
        command = command == null ? null : List.copyOf(command);
        env = copyOrdered(env);
        volumes = copyOrdered(volumes);
        binds = copyOrdered(binds);
        publications = List.copyOf(publications);
        ownerLabels = copyOrdered(ownerLabels);
        tmpfs = copyOrdered(tmpfs);
    }

    private static <V> @NonNull Map<String, V> copyOrdered(@NonNull Map<String, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * The one way a kind declares a workload: the five components nothing may omit as
     * arguments, everything else named at the call site.
     *
     * AIDEV-NOTE: this REPLACED a ladder of five positional convenience constructors
     * (2026-08-13). They differed only in arity, so adding a component to the record
     * silently re-pointed any call site one argument short at a SHORTER overload: the
     * 17-arg "widest single publication" shape and the 18-arg canonical one differed
     * only in {@code PortPublication} vs {@code List<PortPublication>}, and
     * StackServiceKind bound to the wrong one on the write that introduced
     * {@code networkLimitMbit}. A builder has no arity, so that class of mis-binding
     * cannot be written.
     *
     * The five REQUIRED arguments are deliberate and must stay arguments: {@code handle}
     * and {@code image} identify the workload, {@code limits} is what capacity books,
     * {@code ownerLabels} is the attribution that must land at create time, and
     * {@code hardening} is the isolation profile the record docblock requires be declared
     * "so a new kind cannot inherit isolation by accident" -- a defaulted builder setter
     * would hand exactly that accident back.
     */
    public static @NonNull Builder builder(@NonNull String handle,
                                           @NonNull String image,
                                           @NonNull ResourceLimits limits,
                                           ContainerHardening.@NonNull Profile hardening,
                                           @NonNull Map<String, String> ownerLabels) {
        return new Builder(handle, image, limits, hardening, ownerLabels);
    }

    /** Names every optional component of a spec; see {@link #builder}. */
    public static final class Builder {

        private final @NonNull String handle;
        private final @NonNull String image;
        private final @NonNull ResourceLimits limits;
        private final ContainerHardening.@NonNull Profile hardening;
        private final @NonNull Map<String, String> ownerLabels;

        private @Nullable List<String> command;
        private @NonNull Map<String, String> env = Map.of();
        private @NonNull Map<String, String> volumes = Map.of();
        private @NonNull Map<String, String> binds = Map.of();
        private @NonNull List<PortPublication> publications = List.of();
        private @Nullable String cloudInitUserData;
        private @Nullable String imageFingerprint;
        private @NonNull ImageOrigin imageOrigin = ImageOrigin.CATALOG;
        private boolean secureBoot;
        private boolean guestAgent = true;
        private @NonNull Map<String, Long> tmpfs = Map.of();
        private @Nullable HealthCheck healthCheck;
        private @Nullable Integer rootDiskGb;
        private @Nullable Integer networkLimitMbit;
        private @Nullable Integer runUser;
        private boolean tty;

        private Builder(@NonNull String handle, @NonNull String image,
                        @NonNull ResourceLimits limits,
                        ContainerHardening.@NonNull Profile hardening,
                        @NonNull Map<String, String> ownerLabels) {
            this.handle = handle;
            this.image = image;
            this.limits = limits;
            this.hardening = hardening;
            this.ownerLabels = ownerLabels;
        }

        /** @param command null keeps the image's own default */
        public @NonNull Builder command(@Nullable List<String> command) {
            this.command = command;
            return this;
        }

        public @NonNull Builder env(@NonNull Map<String, String> env) {
            this.env = env;
            return this;
        }

        public @NonNull Builder volumes(@NonNull Map<String, String> volumes) {
            this.volumes = volumes;
            return this;
        }

        /** Owner-keyed host directories under the volume root; see {@code InstanceVolumes}. */
        public @NonNull Builder binds(@NonNull Map<String, String> binds) {
            this.binds = binds;
            return this;
        }

        /** The single-publication shape every one-port tier declares; null means none. */
        public @NonNull Builder publication(@Nullable PortPublication publication) {
            this.publications = listOf(publication);
            return this;
        }

        public @NonNull Builder publications(@NonNull List<PortPublication> publications) {
            this.publications = List.copyOf(publications);
            return this;
        }

        public @NonNull Builder cloudInitUserData(@Nullable String cloudInitUserData) {
            this.cloudInitUserData = cloudInitUserData;
            return this;
        }

        public @NonNull Builder imageFingerprint(@Nullable String imageFingerprint) {
            this.imageFingerprint = imageFingerprint;
            return this;
        }

        public @NonNull Builder imageOrigin(@NonNull ImageOrigin imageOrigin) {
            this.imageOrigin = imageOrigin;
            return this;
        }

        public @NonNull Builder secureBoot(boolean secureBoot) {
            this.secureBoot = secureBoot;
            return this;
        }

        public @NonNull Builder guestAgent(boolean guestAgent) {
            this.guestAgent = guestAgent;
            return this;
        }

        public @NonNull Builder tmpfs(@NonNull Map<String, Long> tmpfs) {
            this.tmpfs = tmpfs;
            return this;
        }

        public @NonNull Builder healthCheck(@Nullable HealthCheck healthCheck) {
            this.healthCheck = healthCheck;
            return this;
        }

        public @NonNull Builder rootDiskGb(@Nullable Integer rootDiskGb) {
            this.rootDiskGb = rootDiskGb;
            return this;
        }

        public @NonNull Builder networkLimitMbit(@Nullable Integer networkLimitMbit) {
            this.networkLimitMbit = networkLimitMbit;
            return this;
        }

        /** The host uid the workload runs as; null keeps the image's own user. */
        public @NonNull Builder runUser(@Nullable Integer runUser) {
            this.runUser = runUser;
            return this;
        }

        /** Whether the primary process gets a pseudo-terminal; see the record docblock. */
        public @NonNull Builder tty(boolean tty) {
            this.tty = tty;
            return this;
        }

        public @NonNull InstanceSpec build() {
            return new InstanceSpec(this.handle, this.image, this.command, this.env,
                this.volumes, this.binds, this.publications, this.limits, this.hardening,
                this.ownerLabels, this.cloudInitUserData, this.imageFingerprint,
                this.imageOrigin, this.secureBoot, this.guestAgent, this.tmpfs,
                this.healthCheck, this.rootDiskGb, this.networkLimitMbit, this.runUser,
                this.tty);
        }
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
            this.binds, List.copyOf(claimed), this.limits, this.hardening,
            this.ownerLabels, this.cloudInitUserData, this.imageFingerprint, this.imageOrigin,
            this.secureBoot, this.guestAgent, this.tmpfs, this.healthCheck, this.rootDiskGb,
            this.networkLimitMbit, this.runUser, this.tty);
    }

    /** A copy carrying the record's pinned resolved image identity. */
    public @NonNull InstanceSpec withImageFingerprint(@Nullable String fingerprint) {
        return new InstanceSpec(this.handle, this.image, this.command, this.env, this.volumes,
            this.binds, this.publications, this.limits, this.hardening, this.ownerLabels,
            this.cloudInitUserData, fingerprint, this.imageOrigin, this.secureBoot,
            this.guestAgent, this.tmpfs, this.healthCheck, this.rootDiskGb,
            this.networkLimitMbit, this.runUser, this.tty);
    }

    private static @NonNull List<PortPublication> listOf(@Nullable PortPublication one) {
        return one == null ? List.of() : List.of(one);
    }
}
