package be.elevenways.hohenheim.sitetype;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common site type metadata. Lives in src/common so admin UI (client) can
 * access type information without server/Undertow dependencies.
 * Icon and color facets ride the typed TypeDefinition contract.
 */
public interface SiteTypeInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /**
     * Short description shown in the type selector UI.
     */
    String getDescription();
}
