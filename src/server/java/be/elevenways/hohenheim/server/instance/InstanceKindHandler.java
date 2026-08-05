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
     * The host runtime ({@code ServerModel.RUNTIME_*}) records of this kind run on.
     * Placement only offers matching hosts, and a mismatched deploy refuses at client
     * construction -- a Docker kind can never land on an Incus daemon or vice versa.
     */
    default @NonNull String requiredRuntime() {
        return ServerModel.RUNTIME_DOCKER;
    }
}
