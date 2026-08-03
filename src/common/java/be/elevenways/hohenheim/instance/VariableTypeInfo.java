package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common metadata of a template-variable type (the InstanceKindInfo shape): the type
 * enumerates in the admin variable editor, its {@code getSchema()} is the per-type
 * settings sub-form (min/max, options, pattern...), and the server half
 * (VariableTypeHandler) builds the REAL zenit field a value is validated through --
 * typed validation, deliberately not Pterodactyl's rule-strings.
 */
public interface VariableTypeInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /**
     * Whether values of this type are secrets: stored ONLY in the encrypted
     * {@code secret_value} column of instance_variables, masked in forms, never in
     * revisions or logs.
     */
    default boolean isSecretValue() {
        return false;
    }
}
