package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceKindInfo;
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
}
