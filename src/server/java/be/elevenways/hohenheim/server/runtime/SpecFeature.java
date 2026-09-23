package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * THE vocabulary of optional {@link InstanceSpec} capabilities a driver must either deliver
 * or refuse BY NAME: each member knows whether a spec requests it and how to word the
 * refusal, and each driver DECLARES the set it delivers.
 *
 * AIDEV-NOTE: before this, both drivers hand-spelled an if-chain over the spec's optional
 * components, and a component nobody added to the chain of the driver that could not
 * deliver it was silently DROPPED -- the paper-limit shape the whole spec docblock argues
 * against. {@link #requireSupported} asks every member, so a new member is refused by every
 * driver until that driver claims it: the check fails CLOSED. Adding a spec component that
 * a driver may not honour means adding a member here; {@code SpecFeatureVocabularyDriftTest}
 * builds one spec per member (an exhaustive switch) and proves each is judged.
 *
 * AIDEV-NOTE: the refusal text keeps the per-feature reason the drivers used to spell,
 * because it is what an operator reads; the {@code why} names the driver that DOES deliver
 * the feature today, which is descriptive, not a routing decision.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public enum SpecFeature {

    /** Cloud-init user-data delivered to the guest at first boot. */
    CLOUD_INIT(
        spec -> spec.cloudInitUserData() != null && !spec.cloudInitUserData().isBlank(),
        spec -> "cloud-init user-data",
        "cloud-init provisioning is an incus capability, and provisioning that would not"
            + " run must not start"),

    /**
     * A per-workload root-disk quota. MEASURED on daystrom 2026-08-07: Docker's overlayfs on
     * ext4 ACCEPTS {@code --storage-opt size=2G} with exit 0 and then lets 2.5GB be written
     * into the "2G" root, so a driver without an enforcing backing refuses instead.
     */
    ROOT_DISK_SIZE(
        spec -> spec.rootDiskGb() != null,
        spec -> "the " + spec.rootDiskGb() + "GB root disk",
        "a per-container root-disk quota is an incus capability. Docker would accept the"
            + " size and enforce nothing."),

    /**
     * A bandwidth ceiling on the workload's NICs. Docker's API has no bandwidth key at all:
     * shaping would mean a tc qdisc on a veth the daemon re-makes on every restart.
     */
    BANDWIDTH_LIMIT(
        spec -> spec.networkLimitMbit() != null,
        spec -> "the " + spec.networkLimitMbit() + " Mbit/s network limit",
        "a per-workload bandwidth ceiling is an incus capability. Docker has no such control"
            + " and would shape nothing."),

    /** An image an operator published into the daemon's own store (never fetched). */
    PREPARED_IMAGE(
        spec -> spec.imageOrigin() == ImageOrigin.PREPARED,
        spec -> "the prepared image '" + spec.image() + "'",
        "this driver has no prepared-template image store; image_origin=prepared is an incus"
            + " capability, and treating the alias as a registry reference would pull the"
            + " WRONG thing instead of the operator's prepared image"),

    /** An empty workload installed interactively from attached install media. */
    INSTALL_MEDIA(
        spec -> spec.imageOrigin() == ImageOrigin.INSTALL_MEDIA,
        spec -> "an empty workload installed from install media",
        "booting install media is an incus virtual-machine capability"),

    /** The primary process behind a pseudo-terminal ({@code console_kind=tty}). */
    PSEUDO_TERMINAL(
        InstanceSpec::tty,
        spec -> "a pseudo-terminal on the primary process",
        "an interactive console (console_kind=tty) is a docker capability; a pipe must never"
            + " be offered as a terminal"),

    /**
     * RAM-backed scratch mounts. Silently dropping a DECLARED discardable mount would land
     * the workload's "ephemeral" data on persistent storage, the opposite of what was declared.
     */
    TMPFS_MOUNTS(
        spec -> !spec.tmpfs().isEmpty(),
        spec -> "the RAM-backed scratch mounts " + spec.tmpfs().keySet(),
        "tmpfs mounts are a docker capability"),

    /** A runtime-evaluated health probe; a declared gate nobody evaluates always reports healthy. */
    HEALTH_CHECK(
        spec -> spec.healthCheck() != null,
        spec -> "the declared health check",
        "a runtime-evaluated healthcheck is a docker capability"),

    /** A working-directory override for the primary process. */
    WORKDIR(
        spec -> spec.workdir() != null,
        spec -> "the working directory '" + spec.workdir() + "'",
        "overriding the primary process working directory is a docker capability"),

    /** The host uid the workload's processes run as. */
    RUN_USER(
        spec -> spec.runUser() != null,
        spec -> "run user " + spec.runUser(),
        "a driver that cannot map a uid would leave every file root-owned while the surface"
            + " claims otherwise");

    private final @NonNull Predicate<InstanceSpec> requested;
    private final @NonNull Function<InstanceSpec, String> what;
    private final @NonNull String why;

    SpecFeature(@NonNull Predicate<InstanceSpec> requested,
                @NonNull Function<InstanceSpec, String> what, @NonNull String why) {
        this.requested = requested;
        this.what = what;
        this.why = why;
    }

    /** Whether {@code spec} asks for this feature. */
    public boolean requestedBy(@NonNull InstanceSpec spec) {
        return this.requested.test(spec);
    }

    /** The operator-facing refusal of this feature by the named driver. */
    public @NonNull String refusal(@NonNull InstanceSpec spec, @NonNull String driver) {
        return "The " + driver + " driver cannot deliver " + this.what.apply(spec)
            + " declared for '" + spec.handle() + "'; " + this.why;
    }

    /** Every feature {@code spec} requests. */
    public static @NonNull Set<SpecFeature> allRequestedBy(@NonNull InstanceSpec spec) {
        Set<SpecFeature> requested = EnumSet.noneOf(SpecFeature.class);
        for (SpecFeature feature : values()) {
            if (feature.requestedBy(spec)) {
                requested.add(feature);
            }
        }
        return requested;
    }

    /**
     * Refuse {@code spec} when it requests any feature outside {@code supported}.
     *
     * @throws IOException naming the first unsupported feature, before anything is created
     */
    public static void requireSupported(@NonNull InstanceSpec spec,
                                        @NonNull Set<SpecFeature> supported,
                                        @NonNull String driver) throws IOException {
        for (SpecFeature feature : values()) {
            if (feature.requestedBy(spec) && !supported.contains(feature)) {
                throw new IOException(feature.refusal(spec, driver));
            }
        }
    }
}
