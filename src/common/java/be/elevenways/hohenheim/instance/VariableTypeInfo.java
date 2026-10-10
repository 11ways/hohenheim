package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common metadata of a template-variable type (the InstanceKindInfo shape): the type
 * enumerates in the admin variable editor, its {@code getSchema()} is the per-type
 * settings sub-form (min/max, options, pattern...), and the server half
 * (VariableTypeHandler) builds the REAL zenit field a value is validated through --
 * typed validation, deliberately not Pterodactyl's rule-strings.
 */
public interface VariableTypeInfo extends WordedKind {

    @Override
    default @NonNull HohenheimMicrocopy labelScope() {
        return HohenheimMicrocopy.VARIABLE_TYPE;
    }

    @Override
    default @NonNull HohenheimMicrocopy descriptionScope() {
        return HohenheimMicrocopy.VARIABLE_TYPE_DESCRIPTION;
    }

    /**
     * Whether values of this type are secrets: stored ONLY in the encrypted
     * {@code secret_value} column of instance_variables, masked in forms, never in
     * revisions or logs.
     */
    default boolean isSecretValue() {
        return false;
    }
}
