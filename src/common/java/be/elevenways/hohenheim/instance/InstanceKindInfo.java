package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Common instance-kind metadata (the UpstreamKindInfo shape): lives in src/common so the
 * admin UI can enumerate kinds without server dependencies. The kind is the ONE
 * discriminator of an instance -- runtime is implied by it ({@code docker_container}
 * now, {@code system_container} and {@code vm} reserved), because
 * {@code SchemaField.schemaFrom} takes exactly one sibling field.
 */
public interface InstanceKindInfo extends WordedKind {

    @Override
    default @NonNull HohenheimMicrocopy labelScope() {
        return HohenheimMicrocopy.INSTANCE_KIND;
    }

    @Override
    default @NonNull HohenheimMicrocopy descriptionScope() {
        return HohenheimMicrocopy.INSTANCE_KIND_DESCRIPTION;
    }

    /**
     * The "Put something online" group this kind is created under from scratch (no template); null when it is never
     * offered there, e.g. a kind only other machinery creates.
     */
    default @Nullable PutOnlineGroup putOnlineGroup() {
        return null;
    }
}
