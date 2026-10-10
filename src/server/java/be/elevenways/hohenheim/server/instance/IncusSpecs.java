package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.instance.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * THE spec an Incus kind (system container, VM) starts from: the instance's handle, image and its origin, limits,
 * owner labels, root disk and network limit; the kind adds what only it boots with.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class IncusSpecs {

    private IncusSpecs() {
    }

    static InstanceSpec.@NonNull Builder spec(int instanceId, @NonNull Map<String, Object> settings,
                                              int defaultFootprintMb, ContainerHardening.@NonNull Profile hardening) {
        return InstanceSpec.forInstance(instanceId, RawValues.trimmed(settings.get(InstanceKindFields.IMAGE)), settings,
                defaultFootprintMb, hardening)
            .imageOrigin(ImageOrigin.of(settings))
            .rootDiskGb(RootDisk.declaredGb(settings))
            .networkLimitMbit(NetworkBandwidth.declaredMbit(settings));
    }
}
