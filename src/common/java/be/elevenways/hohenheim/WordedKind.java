package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A registry kind whose name and description are its family's words keyed by its id's path, so a kind states only its
 * id.
 *
 * AIDEV-NOTE: never TypeDefinition's default label, which renders the English display name as literal text in every
 * locale; this is the app home the framework phase moves onto TypeDefinition. DeclaredMicrocopyKeysTest requires both
 * words of every production kind in en and nl.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface WordedKind extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /** @return the scope of the family's kind names */
    @NonNull HohenheimMicrocopy labelScope();

    /** @return the scope of the family's kind descriptions */
    @NonNull HohenheimMicrocopy descriptionScope();

    @Override
    default @NonNull Microcopy getLabel() {
        return this.labelScope().of(this.typeId().getPath());
    }

    @Override
    default @NonNull Microcopy getDescription() {
        return this.descriptionScope().of(this.typeId().getPath());
    }
}
