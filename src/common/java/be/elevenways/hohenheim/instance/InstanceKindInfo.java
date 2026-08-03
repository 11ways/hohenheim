package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common instance-kind metadata (the SiteTypeInfo shape): lives in src/common so the
 * admin UI can enumerate kinds without server dependencies. The kind is the ONE
 * discriminator of an instance -- runtime is implied by it ({@code docker_container}
 * now, {@code incus_container} and {@code vm} reserved), because
 * {@code SchemaField.schemaFrom} takes exactly one sibling field.
 */
public interface InstanceKindInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /**
     * Short description shown in the kind selector UI.
     */
    String getDescription();
}
