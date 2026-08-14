package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Server-side half of an instance kind: builds the driver and the driver-facing spec
 * for records of this kind. Implementations are discovered at compile time and
 * register themselves via {@code typeId()} -- adding a kind ships one class,
 * registered nowhere manually (the SiteTypeHandler shape).
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.instance.InstanceKinds#register")
public interface InstanceKindHandler extends InstanceKindInfo {

    /**
     * The driver for this kind on the named host (THE canonical server name,
     * {@code ServerModel.nameOf(canonicalServerId(...))} -- never a re-spelling).
     */
    @NonNull InstanceRuntime runtimeFor(@NonNull String serverName);

    /**
     * The driver-facing spec of one record: handle, image, env, volumes, port, limits
     * and the owner labels the driver MUST stamp at create.
     */
    @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings);

    /**
     * Whether records of this kind carry tenant-authored workloads. Tenant-authored
     * kinds place only on an ADMITTED host (HostAdmission.requireInstancePlacement);
     * an operator-authored kind (SiteContainerKind, writable only through the site
     * tier's system scope) predates host admission and deliberately skips that gate --
     * the same declared difference as its SHARED_BRIDGE network posture.
     */
    default boolean tenantAuthored() {
        return true;
    }

    /**
     * The boundary this kind's workloads put between the guest and the host kernel -- the
     * SECOND axis of the plan's workload declaration, paired against the host's posture by
     * {@code HostAdmission.requirePostureSatisfies}.
     *
     * AIDEV-NOTE: it lives HERE, beside {@link #tenantAuthored()}, and not on the common
     * {@code InstanceKindInfo}: the two together are one declaration of what a kind's
     * workloads are, and the only consumer is the server-side placement gate.
     * {@code InstanceKindInfo} is the admin-UI metadata face (label, icon, description);
     * isolation is a placement FACT, not a selector decoration.
     *
     * AIDEV-NOTE: the default is the WEAKEST boundary on purpose. A kind that forgets to
     * declare is treated as sharing the kernel, so forgetting narrows placement instead of
     * silently promising a hypervisor that is not there.
     */
    default @NonNull WorkloadIsolation isolation() {
        return WorkloadIsolation.SHARED_KERNEL;
    }

    /**
     * Whether records of this kind may ONLY be written by the owning product tier, inside
     * its {@code GeneratedRows} system scope. A generated-only kind cannot be created,
     * edited or re-kinded from the standalone instance form or API at all, which is what
     * keeps the admin instance surface from becoming a second authority over a site's
     * release container or a managed database's engine.
     *
     * AIDEV-NOTE: this is a DECLARATION on the kind, not an if-chain in the write hook.
     * The hook that enforces it ({@code OwnedInstances.install}) names no kind, so a new
     * owned kind is one line here and is protected from the moment it registers -- the
     * previous shape hard-coded {@code site_container} in the hook and would have needed
     * editing for every tier the lowering reaches.
     */
    default boolean generatedOnly() {
        return false;
    }

    /**
     * The host runtime ({@code ServerModel.RUNTIME_*}) records of this kind run on.
     * Placement only offers matching hosts, and a mismatched deploy refuses at client
     * construction -- a Docker kind can never land on an Incus daemon or vice versa.
     */
    default @NonNull String requiredRuntime() {
        return ServerModel.RUNTIME_DOCKER;
    }

    /**
     * Whether this kind's driver can attach and detach devices -- the DECLARED twin of
     * the runtime's {@code DeviceAttachSupport} interface, for surfaces that must know
     * before a daemon exists to ask.
     *
     * AIDEV-NOTE: a declaration rather than an {@code instanceof} because the only way to
     * ask the runtime is {@link #runtimeFor}, which BUILDS a daemon client for a named
     * host -- an unreachable or not-yet-chosen host would then decide whether a tab
     * renders. The enforcement stays where it was ({@code InstanceDevices.requireSupport}
     * refuses {@code devices_unsupported} on the real runtime); this exists so a surface
     * can hide what a kind can only ever refuse. The two agreeing is what
     * {@code InstanceDeviceSurfaceTest} asserts on the Docker tier: tab absent AND the
     * write refused, one journey.
     *
     * AIDEV-NOTE: the default is FALSE, matching the {@code isolation()} stance -- a kind
     * that forgets to declare hides an affordance rather than offering one its driver
     * cannot honour.
     */
    default boolean supportsDevices() {
        return false;
    }

    /**
     * Whether this kind's workloads can boot attached install media (an ISO as a
     * CD-ROM device) -- the declared twin of the driver's own VM-only refusal in
     * {@code ensureCdrom}, for surfaces that must know before a daemon exists to ask
     * (the {@link #supportsDevices()} stance, same reasons, same default).
     */
    default boolean supportsInstallMedia() {
        return false;
    }

    /**
     * Whether a STOPPED workload of this kind can be published as a prepared image
     * (template capture) -- the declared twin of the runtime's
     * {@code ImagePublishSupport}, for the row action that must know before a daemon
     * exists to ask (the {@link #supportsDevices()} stance, same default).
     */
    default boolean supportsTemplateCapture() {
        return false;
    }

    /**
     * Whether these settings declare a workload that needs NO image reference at all
     * (the {@code install_media} origin: an empty VM an operator installs from an
     * attached ISO). The resolve path's blank-image refusal steps aside exactly here
     * and nowhere else.
     *
     * AIDEV-NOTE: default FALSE for the same reason as {@link #supportsDevices()} -- a
     * kind that forgets to declare keeps the refusal, it never silently accepts an
     * imageless record.
     */
    default boolean allowsBlankImage(@NonNull Map<String, Object> settings) {
        return false;
    }

    /**
     * The memory (MB) a record of these settings is ADMITTED AS when they declare no
     * {@code memory_limit_mb} -- the host-capacity denominator, and the cgroup/VM cap the
     * driver then actually applies.
     *
     * AIDEV-NOTE: deliberately abstract, with no interface default. A kind that inherited
     * a footprint would be charged a number nobody chose, and the whole point of this
     * declaration is that the capacity gate can never have a zero denominator. Declaring
     * it is one line; forgetting it is a compile error.
     *
     * AIDEV-NOTE: charge == cap is the invariant, not a coincidence. The booking is only
     * honest because {@code ResourceLimits.fromSettings(settings, defaultFootprintMb(settings))}
     * hands the SAME number to the daemon, so a workload cannot quietly grow past what
     * the ledger booked for it. If a kind ever charges one number and caps another, the
     * gate is decoration again.
     *
     * AIDEV-NOTE: the SETTINGS are the argument because a kind can handle several
     * workload SHAPES (DatabaseContainerKind handles all four engines, whose real
     * startup peaks differ by 250 MiB). A no-argument signature forced such a kind to
     * answer one flat number for every shape, and because charge == cap that flat number
     * became a cgroup ceiling BELOW some engines' measured startup peak -- mysql and
     * mongo spent their whole init pinned against it and were OOM-killed under parallel
     * load. An implementation that has one shape simply ignores the argument.
     *
     * @implSpec must NEVER throw -- {@code InstanceCapacity} calls it from a write hook,
     *           so an unrecognised settings shape returns the kind's LARGEST footprint
     *           (over-booking is the safe direction), not a violation
     */
    int defaultFootprintMb(@NonNull Map<String, Object> settings);

    /**
     * Refuse a candidate host this kind's DEPLOY would refuse anyway, before placement
     * can choose it.
     *
     * AIDEV-NOTE: this exists so the eligible set and the deploy path answer to ONE
     * authority. It must never re-state a rule -- an implementation calls the same code
     * the driver calls (IncusInstanceRuntime.requirePreparedImagePresent is the first
     * one), because a second copy of a refusal is the drift defect this seam removes.
     * Implementations must stay CHEAP on the common path: the prepared-image check does
     * no daemon call at all for a catalog image.
     *
     * @throws Violations naming the reason this host cannot run these settings
     */
    default void requirePlaceableOn(@NonNull String serverName,
                                    @NonNull Map<String, Object> settings) {
    }
}
