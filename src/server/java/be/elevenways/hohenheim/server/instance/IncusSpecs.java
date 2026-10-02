package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
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
        String image = settings.get("image") != null ? String.valueOf(settings.get("image")).trim() : "";
        ImageOrigin imageOrigin = ImageOrigin.fromKey(
            settings.get("image_origin") instanceof String origin ? origin : null);
        return InstanceSpec.builder(ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId), image,
                ResourceLimits.fromSettings(settings, defaultFootprintMb), hardening,
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .imageOrigin(imageOrigin)
            .rootDiskGb(RootDisk.declaredGb(settings))
            .networkLimitMbit(NetworkBandwidth.declaredMbit(settings));
    }
}
