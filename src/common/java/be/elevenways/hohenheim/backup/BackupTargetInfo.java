package be.elevenways.hohenheim.backup;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common backup-target-kind metadata (the InstanceKindInfo shape): lives in src/common
 * so the admin UI can enumerate kinds without server dependencies. The kind is the ONE
 * discriminator of a backup target ({@code filesystem} and {@code ssh} now; object
 * storage reserved), driving the RegistryEnumField and the schemaFrom settings form.
 */
public interface BackupTargetInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /** Short description shown in the kind selector UI. */
    @NonNull Microcopy getDescription();
}
