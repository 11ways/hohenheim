package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceKindInfo;
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
     * The memory (MB) a record of this kind is ADMITTED AS when its settings declare no
     * {@code memory_limit_mb} -- the host-capacity denominator, and the cgroup/VM cap the
     * driver then actually applies.
     *
     * AIDEV-NOTE: deliberately abstract, with no interface default. A kind that inherited
     * a footprint would be charged a number nobody chose, and the whole point of this
     * declaration is that the capacity gate can never have a zero denominator. Declaring
     * it is one line; forgetting it is a compile error.
     *
     * AIDEV-NOTE: charge == cap is the invariant, not a coincidence. The booking is only
     * honest because {@code ResourceLimits.fromSettings(settings, defaultFootprintMb())}
     * hands the SAME number to the daemon, so a workload cannot quietly grow past what
     * the ledger booked for it. If a kind ever charges one number and caps another, the
     * gate is decoration again.
     */
    int defaultFootprintMb();

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
